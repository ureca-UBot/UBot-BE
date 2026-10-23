package com.ubot.chat.service;

import com.pgvector.PGvector;
import com.ubot.ai.dto.AiAnswer;
import com.ubot.ai.progress.AnswerProgress;
import com.ubot.ai.progress.AnswerProgress.Phase;
import com.ubot.ai.dto.AnswerMaterials;
import com.ubot.ai.dto.Location;
import com.ubot.ai.dto.SubQuestionFaqs;
import com.ubot.ai.service.AiService;
import com.ubot.ai.service.FaqGapCandidateSelector;
import com.ubot.chat.context.ChatContext;
import com.ubot.chat.context.ChatContextCollector;
import com.ubot.chat.entity.AnswerAttemptsHistory;
import com.ubot.chat.exception.ChatErrorCode;
import com.ubot.embedding.service.EmbeddingProfileService;
import com.ubot.faq.cache.FaqCacheDecision;
import com.ubot.faq.cache.FaqSemanticCacheService;
import com.ubot.faq.dto.response.FaqSearchResponseDto;
import com.ubot.faq.enums.Intent;
import com.ubot.faq.retrieval.DenseFaqCandidates;
import com.ubot.faq.retrieval.FaqRetrievalService;
import com.ubot.faq.retrieval.RetrievalOutcome;
import com.ubot.faq.retrieval.FaqSentenceFilterService;
import com.ubot.faq.retrieval.FaqSentenceFilterService.MatchedFaq;
import com.ubot.llm.exception.LlmErrorCode;
import com.ubot.llm.exception.LlmException;
import com.ubot.unanswered.dto.UnansweredCandidate;
import com.ubot.unanswered.enums.UnansweredReason;
import com.ubot.unanswered.enums.UnansweredSource;
import com.ubot.unanswered.service.RejectedQuestionDuplicateChecker.QuestionEmbedding;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * 하위 질문 정리·하위 질문별 FAQ 검색·유사도 판정·답변 문장 선별·미응답 처리·intent별 자료 수집·AI 호출로 답변을 계산합니다.
 * 판정할 표지가 없는 질문은 원문으로 먼저 검색하고(이때만 FAQ 시맨틱 캐시를 봅니다), 유사도 기준이나 답변 문장 검사를 넘지 못할 때만 판정 LLM으로
 * 하위 질문을 정리합니다. 결과 저장과 응답 확정은 Worker(ChatJobProcessor·ChatJobCompletionService)가 합니다.
 */
@Slf4j
@Component
public class ChatAnswerProcessor {
	// 반려 전용 기준을 정하기 전에는 관리자 재등록을 자동 생략하지 않습니다.
	private static final OptionalDouble REJECTED_DUPLICATE_THRESHOLD = OptionalDouble.empty();

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
	private final ChatContextCollector chatContextCollector;
	private final SubQuestionPlanner subQuestionPlanner;
	private final FaqSemanticCacheService faqSemanticCacheService;
	private final FaqSentenceFilterService faqSentenceFilterService;
	private final ChatInputGuardService chatInputGuardService;
	private final FaqGapCandidateSelector faqGapCandidateSelector;
	private final EmbeddingProfileService embeddingProfileService;

	public ChatAnswerProcessor(
			FaqRetrievalService faqRetrievalService,
			AiService aiService,
			ChatContextCollector chatContextCollector,
			SubQuestionPlanner subQuestionPlanner,
			FaqSemanticCacheService faqSemanticCacheService,
			FaqSentenceFilterService faqSentenceFilterService,
			ChatInputGuardService chatInputGuardService,
			FaqGapCandidateSelector faqGapCandidateSelector,
			EmbeddingProfileService embeddingProfileService) {
		this.faqRetrievalService = faqRetrievalService;
		this.aiService = aiService;
		this.chatContextCollector = chatContextCollector;
		this.subQuestionPlanner = subQuestionPlanner;
		this.faqSemanticCacheService = faqSemanticCacheService;
		this.faqSentenceFilterService = faqSentenceFilterService;
		this.chatInputGuardService = chatInputGuardService;
		this.faqGapCandidateSelector = faqGapCandidateSelector;
		this.embeddingProfileService = embeddingProfileService;
	}

	/** checkCancellation은 단계 사이마다 호출하며, 취소되었으면 예외를 던집니다. */
	ChatAnswerResult generateAnswer(AnswerAttemptsHistory attempt, Location location, Runnable checkCancellation) {
		return generateAnswer(attempt, location, checkCancellation, new ArrayList<>());
	}

	ChatAnswerResult generateAnswer(AnswerAttemptsHistory attempt, Location location, Runnable checkCancellation,
			List<UnansweredCandidate> unansweredCandidates) {
		long startedAt = System.nanoTime();
		checkCancellation.run();
		// 시도 원문은 보존하고 정리본만 질문 정리·검색·답변 생성에 전달합니다.
		String preparedQuestion = chatInputGuardService.prepareQuestion(attempt.getQuestion());
		Map<String, QuestionEmbedding> queryEmbeddings = new HashMap<>();
		AnswerProgress.report(Phase.SEARCHING);
		SubQuestionPlan plan = subQuestionPlanner.plan(attempt, preparedQuestion);
		checkCancellation.run();
		// 판정할 표지가 없는 질문은 원문으로 먼저 검색하고, 질문 유사도 기준이나 답변 문장 검사를 넘지 못하면 그때 판정 LLM을 부릅니다.
		GatedFaqs firstResult = null;
		// 기준을 넘지 못한 원문 검색 결과입니다. 판정 뒤 원문 전체로 다시 검색할 하위 질문이 있으면
		// 같은 검색·문장 검사를 다시 하지 않고 이 결과를 씁니다(가드가 막아 원문으로 돌린 경우 등).
		GatedFaqs failedOriginal = null;
		if (plan.searchFirst()) {
			String question = preparedQuestion;
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
			rememberEmbedding(queryEmbeddings, question, dense.queryVector());
			SearchedCandidates original = new SearchedCandidates(
					faqRetrievalService.rankCandidates(question, dense, topK), dense.queryVector());
			double threshold = plan.history().isEmpty()
					? confidenceThreshold : Math.max(confidenceThreshold, searchFirstHistoryThreshold);
			Double highest = highestSimilarity(original.outcome().rankedCandidates());
			// 최고 유사도가 원문 먼저 검색의 기준을 넘으면 유사도 기준으로 거른 뒤 답변 문장을 검사합니다.
			// 넘지 못하면 그 기준으로 걸러 근거 없음이 되고, 답변 문장 검사는 하지 않습니다.
			boolean passed = highest != null && highest >= threshold;
			GatedFaqs originalResult = gate(attempt, 1, original, passed ? confidenceThreshold : threshold, checkCancellation);
			if (originalResult.isAnswered()) {
				firstResult = originalResult;
			} else {
				if (passed) {
					log.info("원문 검색에서 통과한 답변 문장이 없어 판정합니다: 시도ID={}, 최고유사도={}, 기준값={}",
							attempt.getId(), highest, threshold);
				} else {
					log.info("원문 검색이 기준에 못 미쳐 판정합니다: 시도ID={}, 최고유사도={}, 기준값={}",
							attempt.getId(), highest, threshold);
				}
				failedOriginal = originalResult;
				plan = subQuestionPlanner.judge(attempt, plan, preparedQuestion);
				checkCancellation.run();
			}
		}
		if (plan.isTooMany()) {
			log.info("하위 질문이 너무 많아 답변하지 않습니다: 시도ID={}, 하위질문수={}, 최대={}",
					attempt.getId(), plan.items().size(), SubQuestionPlan.MAX_SUB_QUESTIONS);
			return ChatAnswerResult.failed(ChatErrorCode.TOO_MANY_SUB_QUESTIONS);
		}

		// 하위 질문마다 따로 검색하고 유사도 기준과 답변 문장 검사를 적용합니다. 원문 하나면 기존 흐름과 같습니다.
		// searched에는 원본 FAQ(응답·질문 로그용)를, promptSearched에는 검사를 통과한 답변 문장만 남긴 FAQ(답변 LLM용)를 담습니다.
		List<SubQuestionFaqs> searched = new ArrayList<>();
		List<SubQuestionFaqs> promptSearched = new ArrayList<>();
		List<UnansweredSubQuestion> unanswered = new ArrayList<>();
		for (int index = 0; index < plan.items().size(); index++) {
			int subQuestionIndex = index + 1;
			String searchQuery = plan.items().get(index).searchQuery();
			String userText = plan.items().get(index).userText();
			GatedFaqs result = firstResult;
			if (result == null && failedOriginal != null && searchQuery.equals(preparedQuestion)) {
				// 방금 실패한 원문 검색과 같은 검색이라 검색·답변 문장 검사를 다시 하지 않고 그 결과를 씁니다.
				// 그래서 이 하위 질문은 근거를 찾지 못한 것으로 처리되고, 답변에서는 답할 수 없다고 안내합니다.
				log.info("원문 검색 결과를 다시 씁니다: 시도ID={}, 하위질문순번={}", attempt.getId(), subQuestionIndex);
				result = failedOriginal;
			}
			if (result == null) {
				try {
					SearchedCandidates candidates = searchFaqs(attempt, searchQuery);
					rememberEmbedding(queryEmbeddings, searchQuery, candidates.queryVector());
					result = gate(attempt, subQuestionIndex, candidates, confidenceThreshold,
							checkCancellation);
				} catch (DataAccessException exception) {
					return ChatAnswerResult.failed(ChatErrorCode.VECTOR_SEARCH_FAILED);
				}
			}
			if (!result.isAnswered()) {
				unanswered.add(new UnansweredSubQuestion(
						subQuestionIndex, searchQuery, userText, result.reason(), result.bestResult()));
			}
			searched.add(new SubQuestionFaqs(searchQuery, result.faqs(), userText));
			promptSearched.add(new SubQuestionFaqs(searchQuery, result.promptFaqs(), userText));
		}

		unanswered.forEach(subQuestion -> collectUnanswered(attempt, subQuestion,
				UnansweredSource.RETRIEVAL, queryEmbeddings.get(subQuestion.searchQuery()), unansweredCandidates));
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
		// 공통 FAQ 목록은 같은 FAQ를 한 번만 넣되, 하위 질문마다 고른 답변 문장을 합칩니다(하위 질문별 묶음과 근거를 맞춥니다).
		AnswerMaterials materials = chatContextCollector.collectBySubQuestion(
				new ChatContext(attempt.getUserId(), preparedQuestion, location), promptSearched,
				CommonFaqMerger.merge(searched, promptSearched))
				.withHistory(plan.historyForAnswer());
		AiAnswer answer = aiService.generateAnswer(materials);
		checkCancellation.run();
		if (answer == null || !StringUtils.hasText(answer.answer())) {
			throw new LlmException(LlmErrorCode.LLM_RESPONSE_INVALID);
		}
		if (answer.slotOutcomes() != null) {
			collectSlotUnanswered(attempt, answer.slotOutcomes(), materials, queryEmbeddings, unansweredCandidates);
		} else {
			collectOutputGuardCandidates(attempt, answer, materials, queryEmbeddings, unansweredCandidates);
		}
		return ChatAnswerResult.answered(answer, searched);
	}

	/**
	 * 질문 유사도를 통과한 GENERAL FAQ에만 답변 문장 검사를 적용합니다.
	 * USER_DATA와 STORE_DATA는 실제 조회 자료를 수집하도록 유지합니다.
	 * 남은 FAQ가 없으면 그 사유와 벡터 검색 1위 FAQ를 담습니다.
	 */
	private GatedFaqs gate(AnswerAttemptsHistory attempt, int subQuestionIndex, SearchedCandidates candidates,
			double minimumSimilarity, Runnable checkCancellation) {
		RetrievalOutcome outcome = candidates.outcome();
		// Reranker를 켰으면 다시 정렬한 후보 전체가, 아니면 벡터 검색 상위 topK개가 옵니다.
		List<FaqSearchResponseDto> results = outcome.rankedCandidates();
		checkCancellation.run();
		log.info("FAQ 유사도 검색을 완료했습니다: 시도ID={}, 하위질문순번={}, 재검색의도={}, 결과수={}, 최고유사도={}, "
						+ "Reranker정렬={}, Reranker실패사유={}, 검색결과=[{}]",
				attempt.getId(), subQuestionIndex, attempt.getIntent(), results == null ? 0 : results.size(),
				highestSimilarity(results), outcome.reranked(), outcome.fallbackReason(), formatSearchResults(results));

		if (results == null || results.isEmpty()) {
			log.info("FAQ 검색 결과가 없습니다: 시도ID={}, 하위질문순번={}", attempt.getId(), subQuestionIndex);
			return GatedFaqs.failed(UnansweredReason.NO_FAQ, null);
		}
		// 유사도 기준은 코사인 유사도에만 적용합니다. 다시 정렬한 후보 전체에서 거른 뒤에 topK개로 줄여야,
		// 상위 topK개를 먼저 잘라 그 아래의 기준 통과 후보를 잃는 일이 없습니다.
		List<FaqSearchResponseDto> filteredResults = results.stream()
				.filter(result -> Double.isFinite(result.similarityScore())
						&& result.similarityScore() >= minimumSimilarity)
				.limit(topK)
				.toList();
		if (filteredResults.isEmpty()) {
			log.info("FAQ 유사도가 기준값 미만입니다: 시도ID={}, 하위질문순번={}, 최고유사도={}, 기준값={}",
					attempt.getId(), subQuestionIndex, highestSimilarity(results), minimumSimilarity);
			return GatedFaqs.failed(UnansweredReason.INSUFFICIENT_FAQ, outcome.denseTop1());
		}
		List<FaqSearchResponseDto> sentenceCandidates = filteredResults.stream()
				.filter(faq -> faq.intent() == Intent.GENERAL)
				.toList();
		log.info("FAQ 답변 문장 검색을 시작합니다: 시도ID={}, 하위질문순번={}, 문장검사대상FAQ수={}",
				attempt.getId(), subQuestionIndex, sentenceCandidates.size());
		List<MatchedFaq> matched = sentenceCandidates.isEmpty() ? List.of()
				: faqSentenceFilterService.filter(candidates.queryVector(), sentenceCandidates);
		checkCancellation.run();
		log.info("FAQ 답변 문장 검색을 완료했습니다: 시도ID={}, 하위질문순번={}, 통과FAQ수={}, 통과FAQ ID={}",
				attempt.getId(), subQuestionIndex, matched.size(), matched.stream().map(item -> item.faq().faqId()).toList());
		Map<Long, String> matchedAnswers = matched.stream()
				.collect(Collectors.toMap(item -> item.faq().faqId(), MatchedFaq::answerSentences));
		List<FaqSearchResponseDto> originalFaqs = new ArrayList<>();
		List<FaqSearchResponseDto> promptFaqs = new ArrayList<>();
		for (FaqSearchResponseDto faq : filteredResults) {
			boolean generalFaq = faq.intent() == Intent.GENERAL;
			String answer = generalFaq ? matchedAnswers.get(faq.faqId()) : faq.answer();
			if (generalFaq && answer == null) {
				continue;
			}
			originalFaqs.add(faq);
			promptFaqs.add(new FaqSearchResponseDto(faq.faqId(), faq.question(), answer,
					faq.similarityScore(), faq.intent(), faq.variantId(), faq.variantQuestion(), faq.cacheable(),
					faq.categoryName()));
		}
		if (originalFaqs.isEmpty()) {
			log.info("답변 근거로 사용할 FAQ가 없습니다: 시도ID={}, 하위질문순번={}", attempt.getId(), subQuestionIndex);
			return GatedFaqs.failed(UnansweredReason.INSUFFICIENT_FAQ, outcome.denseTop1());
		}
		return new GatedFaqs(originalFaqs, promptFaqs, null, null);
	}

	/** 재검색 attempt는 사용자가 고른 intent의 FAQ만, 그 외에는 전체 FAQ에서 검색합니다. */
	private SearchedCandidates searchFaqs(AnswerAttemptsHistory attempt, String searchQuery) {
		DenseFaqCandidates dense = faqRetrievalService.searchCandidates(searchQuery, requestedIntent(attempt), topK, 0);
		return new SearchedCandidates(faqRetrievalService.rankCandidates(searchQuery, dense, topK), dense.queryVector());
	}

	private Intent requestedIntent(AnswerAttemptsHistory attempt) {
		return attempt.isResearch() ? attempt.getIntent() : null;
	}

	private long elapsedMillis(long startedAt) {
		return (System.nanoTime() - startedAt) / 1_000_000;
	}

	/** 같은 검색어·같은 실행 프로필의 벡터만 저장 처리로 전달합니다. */
	private void rememberEmbedding(Map<String, QuestionEmbedding> queryEmbeddings, String searchQuery, PGvector vector) {
		if (vector == null) return;
		try {
			// FAQ 검색에서 이미 확정한 같은 프로필입니다. 설정은 실행 중에 바뀌지 않습니다.
			Long profileId = embeddingProfileService.getCurrentProfileId();
			if (profileId != null && profileId > 0) {
				queryEmbeddings.put(searchQuery, new QuestionEmbedding(searchQuery, profileId, vector));
			}
		} catch (RuntimeException exception) {
			log.warn("검색 벡터의 재사용 정보를 가져오지 못했습니다", exception);
		}
	}

	private void collectOutputGuardCandidates(AnswerAttemptsHistory attempt, AiAnswer answer, AnswerMaterials materials,
			Map<String, QuestionEmbedding> queryEmbeddings, List<UnansweredCandidate> unansweredCandidates) {
		if (attempt.isResearch()) return;
		List<FaqGapCandidateSelector.Candidate> candidates;
		try {
			candidates = faqGapCandidateSelector.select(answer.outputGuardResult(), materials, answer.storeMap());
		} catch (RuntimeException exception) {
			// 검토 후보의 부가 처리 실패가 이미 검사한 답변·재시도 정책을 바꾸지 않도록 분리합니다.
			log.warn("출력 가드의 FAQ 보완 후보를 선별하지 못했습니다: 시도ID={}", attempt.getId(), exception);
			return;
		}
		for (var candidate : candidates) {
			int index = candidate.subQuestionIndex() - 1;
			FaqSearchResponseDto bestResult = index >= 0 && index < materials.subQuestions().size()
					&& !materials.subQuestions().get(index).faqs().isEmpty()
					? materials.subQuestions().get(index).faqs().getFirst() : null;
			collectUnanswered(attempt, new UnansweredSubQuestion(candidate.subQuestionIndex(),
					candidate.searchQuery(), candidate.userText(), UnansweredReason.INSUFFICIENT_FAQ, bestResult),
					UnansweredSource.OUTPUT_GUARD, queryEmbeddings.get(candidate.searchQuery()), unansweredCandidates);
		}
	}

	/**
	 * 칸 형식 답변에서 답하지 못한 하위 질문을 미응답으로 모읍니다. ANSWER 칸은 답변으로 나갔으므로 미응답이 아니고,
	 * OUT_OF_SCOPE는 통신 상담 주제가 아니라 FAQ로 보완할 대상이 아니므로 모으지 않습니다.
	 * <ul>
	 *   <li>모델이 ABSTAIN: LLM_ABSTAIN / LLM_MODEL</li>
	 *   <li>모델이 CONFLICT: LLM_CONFLICT / LLM_MODEL</li>
	 *   <li>모델은 ANSWER였는데 출력 가드가 내용을 지워 ABSTAIN이 됨: INSUFFICIENT_FAQ / OUTPUT_GUARD</li>
	 * </ul>
	 * 근거 FAQ를 찾지 못한 하위 질문은 검색 단계에서 이미 저장했으므로 넘깁니다. 칸이 하나뿐이면(원문 조각 없이 나누지 않고 넣은 경우)
	 * 그 칸의 결과를 모든 하위 질문에 적용합니다. 정책 위반으로 답변 전체가 거부되면 이 단계까지 오지 않습니다.
	 */
	private void collectSlotUnanswered(AnswerAttemptsHistory attempt, List<AiAnswer.SlotOutcome> outcomes,
			AnswerMaterials materials, Map<String, QuestionEmbedding> queryEmbeddings,
			List<UnansweredCandidate> unansweredCandidates) {
		if (attempt.isResearch() || outcomes.isEmpty()) return;
		List<SubQuestionFaqs> subQuestions = materials.subQuestions();
		for (int index = 0; index < subQuestions.size(); index++) {
			SubQuestionFaqs subQuestion = subQuestions.get(index);
			if (!subQuestion.isAnswered()) continue;
			AiAnswer.SlotOutcome outcome = outcomes.size() == subQuestions.size() ? outcomes.get(index) : outcomes.getFirst();
			// 비로그인 등으로 가입 정보를 조회할 수 없어 답하지 못한 것은 FAQ로 보완할 대상이 아닙니다.
			if (outcome.lookupUnavailable()) continue;
			UnansweredReason reason;
			UnansweredSource source;
			if (outcome.changedByGuard()) {
				reason = UnansweredReason.INSUFFICIENT_FAQ;
				source = UnansweredSource.OUTPUT_GUARD;
			} else if ("ABSTAIN".equals(outcome.status())) {
				reason = UnansweredReason.LLM_ABSTAIN;
				source = UnansweredSource.LLM_MODEL;
			} else if ("CONFLICT".equals(outcome.status())) {
				reason = UnansweredReason.LLM_CONFLICT;
				source = UnansweredSource.LLM_MODEL;
			} else {
				continue;
			}
			collectUnanswered(attempt, new UnansweredSubQuestion(index + 1, subQuestion.searchQuery(),
					subQuestion.userText(), reason, subQuestion.faqs().getFirst()),
					source, queryEmbeddings.get(subQuestion.searchQuery()), unansweredCandidates);
		}
	}

	private void collectUnanswered(AnswerAttemptsHistory attempt, UnansweredSubQuestion subQuestion,
			UnansweredSource source, QuestionEmbedding reusableEmbedding, List<UnansweredCandidate> unansweredCandidates) {
		if (attempt.isResearch()) return;
		FaqSearchResponseDto bestResult = subQuestion.bestResult();
		List<Float> queryEmbedding = null;
		if (reusableEmbedding != null) {
			queryEmbedding = new ArrayList<>();
			for (float value : reusableEmbedding.vector().toArray()) queryEmbedding.add(value);
		}
		unansweredCandidates.add(new UnansweredCandidate(
				subQuestion.index(), subQuestion.searchQuery(),
				// 안전한 원문 조각이 없으면 제거한 공격을 포함한 원문 전체를 복원하지 않습니다.
				subQuestion.userText() == null ? "" : subQuestion.userText(), subQuestion.reason(),
				bestResult == null ? null : bestResult.faqId(), bestResult == null ? null : bestResult.similarityScore(),
				source, reusableEmbedding == null ? null : reusableEmbedding.profileId(), queryEmbedding,
				source == UnansweredSource.OUTPUT_GUARD && REJECTED_DUPLICATE_THRESHOLD.isPresent()
						? REJECTED_DUPLICATE_THRESHOLD.getAsDouble() : null));
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

	/**
	 * 검색 결과 하나에 유사도 기준과 답변 문장 검사를 적용한 결과입니다. faqs는 원본 FAQ(응답·질문 로그용),
	 * promptFaqs는 통과한 답변 문장만 남긴 FAQ(답변 LLM용)입니다. 통과한 FAQ가 없을 때만 reason이 있고,
	 * bestResult는 그때 미응답 질문에 남길 벡터 검색 1위 FAQ입니다(검색 결과가 없었으면 null).
	 */
	private record GatedFaqs(List<FaqSearchResponseDto> faqs, List<FaqSearchResponseDto> promptFaqs,
			UnansweredReason reason, FaqSearchResponseDto bestResult) {

		static GatedFaqs failed(UnansweredReason reason, FaqSearchResponseDto bestResult) {
			return new GatedFaqs(List.of(), List.of(), reason, bestResult);
		}

		boolean isAnswered() {
			return !faqs.isEmpty();
		}
	}

	/** 하위 질문 하나의 검색 결과와, 답변 문장 검사에 쓸 그 검색어의 벡터입니다. */
	private record SearchedCandidates(RetrievalOutcome outcome, PGvector queryVector) {
	}
}
