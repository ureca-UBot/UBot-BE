package com.ubot.chat.service;

import com.ubot.ai.dto.AiAnswer;
import com.ubot.ai.progress.AnswerProgress;
import com.ubot.ai.progress.AnswerProgress.Phase;
import com.ubot.ai.dto.AnswerMaterials;
import com.ubot.ai.dto.Location;
import com.ubot.ai.dto.SubQuestionFaqs;
import com.ubot.ai.service.AiService;
import com.ubot.chat.context.ChatContext;
import com.ubot.chat.context.ChatContextCollector;
import com.ubot.chat.entity.AnswerAttemptsHistory;
import com.ubot.chat.exception.ChatErrorCode;
import com.ubot.faq.cache.FaqCacheDecision;
import com.ubot.faq.cache.FaqSemanticCacheService;
import com.ubot.faq.dto.response.FaqSearchResponseDto;
import com.ubot.faq.enums.Intent;
import com.ubot.faq.retrieval.DenseFaqCandidates;
import com.ubot.faq.retrieval.FaqRetrievalService;
import com.ubot.faq.retrieval.RetrievalOutcome;
import com.ubot.llm.exception.LlmErrorCode;
import com.ubot.llm.exception.LlmException;
import com.ubot.unanswered.enums.UnansweredReason;
import com.ubot.unanswered.service.UnansweredQuestionService;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * 하위 질문 정리·하위 질문별 FAQ 검색·유사도 판정·미응답 처리·intent별 자료 수집·AI 호출로 답변을 계산합니다.
 * 판정할 표지가 없는 질문은 원문으로 먼저 검색하고(이때만 FAQ 시맨틱 캐시를 봅니다), 기준을 넘지 못할 때만 판정 LLM으로
 * 하위 질문을 정리합니다. 결과 저장과 응답 확정은 Worker(ChatJobProcessor·ChatJobCompletionService)가 합니다.
 */
@Slf4j
@Component
public class ChatAnswerProcessor {
	@Value("${CHAT_TOP_K:3}")
	private int topK;

	@Value("${CHAT_CONFIDENCE_THRESHOLD:0.75}")
	private double confidenceThreshold;

	/**
	 * 이전 대화가 있을 때 원문 검색만으로 끝낼 기준입니다. 이전 대화가 없으면 유사도 기준을 씁니다.
	 * 기본값은 유사도 기준과 같고, 나중에 튜닝할 수 있게 따로 둡니다. 유사도 기준보다 낮게 주면 유사도 기준을 씁니다.
	 */
	@Value("${CHAT_SEARCH_FIRST_HISTORY_THRESHOLD:0.75}")
	private double searchFirstHistoryThreshold;

	private final FaqRetrievalService faqRetrievalService;
	private final AiService aiService;
	private final UnansweredQuestionService unansweredQuestionService;
	private final ChatContextCollector chatContextCollector;
	private final SubQuestionPlanner subQuestionPlanner;
	private final FaqSemanticCacheService faqSemanticCacheService;

	public ChatAnswerProcessor(
			FaqRetrievalService faqRetrievalService,
			AiService aiService,
			UnansweredQuestionService unansweredQuestionService,
			ChatContextCollector chatContextCollector,
			SubQuestionPlanner subQuestionPlanner,
			FaqSemanticCacheService faqSemanticCacheService) {
		this.faqRetrievalService = faqRetrievalService;
		this.aiService = aiService;
		this.unansweredQuestionService = unansweredQuestionService;
		this.chatContextCollector = chatContextCollector;
		this.subQuestionPlanner = subQuestionPlanner;
		this.faqSemanticCacheService = faqSemanticCacheService;
	}

	/** checkCancellation은 단계 사이마다 호출하며, 취소되었으면 예외를 던집니다. */
	ChatAnswerResult generateAnswer(AnswerAttemptsHistory attempt, Location location, Runnable checkCancellation) {
		long startedAt = System.nanoTime();
		checkCancellation.run();
		AnswerProgress.report(Phase.SEARCHING);
		SubQuestionPlan plan = subQuestionPlanner.plan(attempt);
		checkCancellation.run();
		// 판정할 표지가 없는 질문은 원문으로 먼저 검색하고, 기준을 넘지 못하면 그때 판정 LLM을 부릅니다.
		RetrievalOutcome firstOutcome = null;
		// 기준을 넘지 못한 원문 검색 결과와 그 기준입니다. 판정 뒤 원문 전체로 다시 검색할 하위 질문이 있으면
		// 같은 검색을 다시 하지 않고 이 결과를 같은 기준으로 씁니다(가드가 막아 원문으로 돌린 경우 등).
		RetrievalOutcome failedOriginal = null;
		double failedOriginalThreshold = confidenceThreshold;
		if (plan.searchFirst()) {
			String question = attempt.getQuestion();
			Intent requestedIntent = requestedIntent(attempt);
			DenseFaqCandidates dense;
			try {
				dense = faqRetrievalService.searchCandidates(question, requestedIntent, topK,
						faqSemanticCacheService.minimumCandidateCount(requestedIntent));
			} catch (DataAccessException exception) {
				return ChatAnswerResult.failed(ChatErrorCode.VECTOR_SEARCH_FAILED);
			}
			checkCancellation.run();
			// 시맨틱 캐시는 분리·멀티턴 표지가 없어 원문 하나로 검색하는 이 경로에서만 봅니다.
			// 표지가 있으면 FAQ 하나의 공식 답변으로 끝내면 나머지 질문이 빠지거나 이전 대화의 맥락을 잃습니다.
			FaqCacheDecision cacheDecision = faqSemanticCacheService.evaluate(requestedIntent, dense.candidates());
			checkCancellation.run();
			Optional<FaqSearchResponseDto> cachedFaq = cacheDecision.faq();
			log.info("FAQ_CACHE decision: attemptId={}, reason={}, faqId={}, score={}, margin={}",
					attempt.getId(), cacheDecision.reason(),
					cachedFaq.map(FaqSearchResponseDto::faqId).orElse(null),
					cacheDecision.similarity(), cacheDecision.margin());
			if (cachedFaq.isPresent()) {
				FaqSearchResponseDto officialFaq = cachedFaq.get();
				log.info("CHAT route: attemptId={}, source=OFFICIAL_FAQ, faqIds={}, preparationMs={}",
						attempt.getId(), List.of(officialFaq.faqId()), elapsedMillis(startedAt));
				return ChatAnswerResult.answered(new AiAnswer(officialFaq.answer(), null, false),
						List.of(new SubQuestionFaqs(question, List.of(officialFaq), question)));
			}
			// MISS이면 같은 검색 후보로 이어갑니다. 임베딩·검색은 반복하지 않습니다.
			firstOutcome = faqRetrievalService.rankCandidates(question, dense, topK);
			double threshold = plan.history().isEmpty()
					? confidenceThreshold : Math.max(confidenceThreshold, searchFirstHistoryThreshold);
			Double highest = highestSimilarity(firstOutcome.rankedCandidates());
			if (highest == null || highest < threshold) {
				log.info("원문 검색이 기준에 못 미쳐 판정합니다: 시도ID={}, 최고유사도={}, 기준값={}",
						attempt.getId(), highest, threshold);
				failedOriginal = firstOutcome;
				failedOriginalThreshold = threshold;
				firstOutcome = null;
				plan = subQuestionPlanner.judge(attempt, plan);
				checkCancellation.run();
			}
		}
		if (plan.isTooMany()) {
			log.info("하위 질문이 너무 많아 답변하지 않습니다: 시도ID={}, 하위질문수={}, 최대={}",
					attempt.getId(), plan.items().size(), SubQuestionPlan.MAX_SUB_QUESTIONS);
			return ChatAnswerResult.failed(ChatErrorCode.TOO_MANY_SUB_QUESTIONS);
		}

		// 하위 질문마다 따로 검색하고 유사도 기준을 적용합니다. 원문 하나면 기존 흐름과 같습니다.
		List<SubQuestionFaqs> searched = new ArrayList<>();
		List<UnansweredSubQuestion> unanswered = new ArrayList<>();
		for (int index = 0; index < plan.items().size(); index++) {
			int subQuestionIndex = index + 1;
			String searchQuery = plan.items().get(index).searchQuery();
			String userText = plan.items().get(index).userText();
			RetrievalOutcome outcome = firstOutcome;
			double minimumSimilarity = confidenceThreshold;
			if (outcome == null && failedOriginal != null && searchQuery.equals(attempt.getQuestion())) {
				// 방금 기준을 넘지 못한 원문 검색과 같은 검색이라 다시 하지 않고, 그 결과를 넘지 못한 기준으로 판단합니다.
				// 그래서 이 하위 질문은 근거를 찾지 못한 것으로 처리되고, 답변에서는 답할 수 없다고 안내합니다.
				log.info("원문 검색 결과를 다시 씁니다: 시도ID={}, 하위질문순번={}", attempt.getId(), subQuestionIndex);
				outcome = failedOriginal;
				minimumSimilarity = failedOriginalThreshold;
			}
			if (outcome == null) {
				try {
					outcome = searchFaqs(attempt, searchQuery);
				} catch (DataAccessException exception) {
					return ChatAnswerResult.failed(ChatErrorCode.VECTOR_SEARCH_FAILED);
				}
			}
			// Reranker를 켰으면 다시 정렬한 후보 전체가, 아니면 벡터 검색 상위 topK개가 옵니다.
			List<FaqSearchResponseDto> results = outcome.rankedCandidates();
			checkCancellation.run();
			log.info("FAQ 유사도 검색을 완료했습니다: 시도ID={}, 하위질문순번={}, 재검색의도={}, 결과수={}, 최고유사도={}, "
							+ "Reranker정렬={}, Reranker실패사유={}, 검색결과=[{}]",
					attempt.getId(), subQuestionIndex, attempt.getIntent(), results == null ? 0 : results.size(),
					highestSimilarity(results), outcome.reranked(), outcome.fallbackReason(), formatSearchResults(results));

			if (results == null || results.isEmpty()) {
				log.info("FAQ 검색 결과가 없습니다: 시도ID={}, 하위질문순번={}", attempt.getId(), subQuestionIndex);
				unanswered.add(new UnansweredSubQuestion(subQuestionIndex, searchQuery, userText, UnansweredReason.NO_FAQ, null));
				searched.add(new SubQuestionFaqs(searchQuery, List.of(), userText));
				continue;
			}
			// 유사도 기준은 코사인 유사도에만 적용합니다. 다시 정렬한 후보 전체에서 거른 뒤에 topK개로 줄여야,
			// 상위 topK개를 먼저 잘라 그 아래의 기준 통과 후보를 잃는 일이 없습니다.
			double threshold = minimumSimilarity;
			List<FaqSearchResponseDto> filteredResults = results.stream()
					.filter(result -> Double.isFinite(result.similarityScore())
							&& result.similarityScore() >= threshold)
					.limit(topK)
					.toList();
			if (filteredResults.isEmpty()) {
				log.info("FAQ 유사도가 기준값 미만입니다: 시도ID={}, 하위질문순번={}, 최고유사도={}, 기준값={}",
						attempt.getId(), subQuestionIndex, highestSimilarity(results), threshold);
				unanswered.add(new UnansweredSubQuestion(
						subQuestionIndex, searchQuery, userText, UnansweredReason.INSUFFICIENT_FAQ, outcome.denseTop1()));
			}
			searched.add(new SubQuestionFaqs(searchQuery, filteredResults, userText));
		}

		unanswered.forEach(subQuestion -> createUnansweredQuestion(attempt, subQuestion));
		if (searched.stream().noneMatch(SubQuestionFaqs::isAnswered)) {
			// 모든 하위 질문이 검색 결과조차 없을 때만 NO_FAQ이고, 하나라도 후보가 있었으면 기준 미달입니다.
			boolean noResultsAtAll = unanswered.stream().allMatch(subQuestion -> subQuestion.reason() == UnansweredReason.NO_FAQ);
			return ChatAnswerResult.failed(noResultsAtAll ? ChatErrorCode.NO_FAQ : ChatErrorCode.INSUFFICIENT_FAQ);
		}

		checkCancellation.run();
		log.info("CHAT route: attemptId={}, source=LLM, faqIds={}, preparationMs={}",
				attempt.getId(), searched.stream().flatMap(subQuestion -> subQuestion.faqs().stream())
						.map(FaqSearchResponseDto::faqId).toList(),
				elapsedMillis(startedAt));
		// 검색된 FAQ의 intent별로 답변 자료를 모은 뒤 LLM을 한 번 호출합니다. 근거를 못 찾은 하위 질문은 그 부분만 답할 수 없다고 안내합니다.
		// 이전 대화는 이번 질문이 이전 대화를 써야 할 때만 넘깁니다(이전 답변의 틀린 내용이 옮겨 가는 것을 줄입니다).
		AnswerProgress.report(Phase.COLLECTING_CONTEXT);
		AnswerMaterials materials = chatContextCollector.collectBySubQuestion(
				new ChatContext(attempt.getUserId(), attempt.getQuestion(), location), searched)
				.withHistory(plan.historyForAnswer());
		AiAnswer answer = aiService.generateAnswer(materials);
		checkCancellation.run();
		if (answer == null || !StringUtils.hasText(answer.answer())) {
			throw new LlmException(LlmErrorCode.LLM_RESPONSE_INVALID);
		}
		return ChatAnswerResult.answered(answer, searched);
	}

	/** 재검색 attempt는 사용자가 고른 intent의 FAQ만, 그 외에는 전체 FAQ에서 검색합니다. */
	private RetrievalOutcome searchFaqs(AnswerAttemptsHistory attempt, String searchQuery) {
		DenseFaqCandidates dense = faqRetrievalService.searchCandidates(searchQuery, requestedIntent(attempt), topK, 0);
		return faqRetrievalService.rankCandidates(searchQuery, dense, topK);
	}

	private Intent requestedIntent(AnswerAttemptsHistory attempt) {
		return attempt.isResearch() ? attempt.getIntent() : null;
	}

	private long elapsedMillis(long startedAt) {
		return (System.nanoTime() - startedAt) / 1_000_000;
	}

	private void createUnansweredQuestion(AnswerAttemptsHistory attempt, UnansweredSubQuestion subQuestion) {
		// 재검색은 사용자가 의도를 직접 고른 결과라서, FAQ 부족 신호(미응답 질문)로 쌓지 않습니다.
		if (attempt.isResearch()) {
			return;
		}
		FaqSearchResponseDto bestResult = subQuestion.bestResult();
		try {
			unansweredQuestionService.createUnansweredQuestion(
					attempt.getId(),
					subQuestion.index(),
					subQuestion.searchQuery(),
					// 원문을 자를 근거가 없었으면 관리자 화면에는 원문 전체를 보여 줍니다.
					subQuestion.userText() == null ? attempt.getQuestion() : subQuestion.userText(),
					subQuestion.reason(),
					bestResult == null ? null : bestResult.faqId(),
					bestResult == null ? null : bestResult.similarityScore());
		} catch (RuntimeException exception) {
			log.warn("미응답 질문 저장에 실패했습니다: 시도ID={}, 하위질문순번={}", attempt.getId(), subQuestion.index(), exception);
		}
	}

	private Double highestSimilarity(List<FaqSearchResponseDto> results) {
		if (results == null) {
			return null;
		}
		return results.stream()
				.map(FaqSearchResponseDto::similarityScore)
				.filter(score -> score != null && Double.isFinite(score))
				.max(Double::compareTo)
				.orElse(null);
	}

	private String formatSearchResults(List<FaqSearchResponseDto> results) {
		if (results == null || results.isEmpty()) {
			return "없음";
		}
		return results.stream()
				.map(result -> "FAQ ID=" + result.faqId()
						+ ", FAQ 질문=" + normalizeForLog(result.question())
						+ ", 매칭 variant ID=" + result.variantId()
						+ ", 매칭 variant 질문=" + normalizeForLog(result.variantQuestion())
						+ ", 유사도=" + result.similarityScore())
				.collect(java.util.stream.Collectors.joining(" | "));
	}

	private String normalizeForLog(String value) {
		return value == null ? "없음" : value.replaceAll("[\\r\\n\\t]+", " ");
	}

	/** 근거를 찾지 못한 하위 질문입니다. userText는 원문을 자를 근거가 없었으면, bestResult는 검색 결과가 없었으면 null입니다. */
	private record UnansweredSubQuestion(
			int index, String searchQuery, String userText, UnansweredReason reason, FaqSearchResponseDto bestResult) {
	}
}
