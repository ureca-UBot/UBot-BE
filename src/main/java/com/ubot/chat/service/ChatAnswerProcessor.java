package com.ubot.chat.service;

import com.ubot.ai.dto.AiAnswer;
import com.ubot.ai.progress.AnswerProgress;
import com.ubot.ai.progress.AnswerProgress.Phase;
import com.ubot.ai.dto.AnswerMaterials;
import com.ubot.ai.dto.Location;
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
import com.ubot.faq.retrieval.FaqSentenceFilterService;
import com.ubot.faq.retrieval.FaqSentenceFilterService.MatchedFaq;
import com.ubot.llm.exception.LlmErrorCode;
import com.ubot.llm.exception.LlmException;
import com.ubot.unanswered.enums.UnansweredReason;
import com.ubot.unanswered.service.UnansweredQuestionService;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * FAQ 검색·유사도 판정·미응답 처리·intent별 자료 수집·AI 호출로 답변을 계산합니다. 결과 저장과 응답 확정은
 * Worker(ChatJobProcessor·ChatJobCompletionService)가 합니다.
 */
@Slf4j
@Component
public class ChatAnswerProcessor {
	@Value("${CHAT_TOP_K:3}")
	private int topK;

	@Value("${CHAT_CONFIDENCE_THRESHOLD:0.75}")
	private double confidenceThreshold;

	private final FaqRetrievalService faqRetrievalService;
	private final AiService aiService;
	private final UnansweredQuestionService unansweredQuestionService;
	private final ChatContextCollector chatContextCollector;
	private final FaqSemanticCacheService faqSemanticCacheService;
	private final FaqSentenceFilterService faqSentenceFilterService;

	public ChatAnswerProcessor(
			FaqRetrievalService faqRetrievalService,
			AiService aiService,
			UnansweredQuestionService unansweredQuestionService,
			ChatContextCollector chatContextCollector,
			FaqSemanticCacheService faqSemanticCacheService,
			FaqSentenceFilterService faqSentenceFilterService) {
		this.faqRetrievalService = faqRetrievalService;
		this.aiService = aiService;
		this.unansweredQuestionService = unansweredQuestionService;
		this.chatContextCollector = chatContextCollector;
		this.faqSemanticCacheService = faqSemanticCacheService;
		this.faqSentenceFilterService = faqSentenceFilterService;
	}

	/** checkCancellation은 단계 사이마다 호출하며, 취소되었으면 예외를 던집니다. */
	ChatAnswerResult generateAnswer(AnswerAttemptsHistory attempt, Location location, Runnable checkCancellation) {
		long startedAt = System.nanoTime();
		checkCancellation.run();
		AnswerProgress.report(Phase.SEARCHING);
		Intent requestedIntent = attempt.isResearch() ? attempt.getIntent() : null;
		DenseFaqCandidates dense;
		try {
			dense = faqRetrievalService.searchCandidates(attempt.getQuestion(), requestedIntent, topK,
					faqSemanticCacheService.minimumCandidateCount(requestedIntent));
		} catch (DataAccessException exception) {
			return ChatAnswerResult.failed(ChatErrorCode.VECTOR_SEARCH_FAILED);
		}
		checkCancellation.run();
		log.info("FAQ 질문 벡터 검색을 완료했습니다: 시도ID={}, 재검색의도={}, 결과수={}, 최고유사도={}, 검색시간={}ms, 검색결과=[{}]",
				attempt.getId(), attempt.getIntent(), dense.candidates().size(),
				highestSimilarity(dense.candidates()), dense.searchMillis(), formatSearchResults(dense.candidates()));
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
			return ChatAnswerResult.answered(new AiAnswer(officialFaq.answer(), null, false), List.of(officialFaq));
		}
		RetrievalOutcome outcome = faqRetrievalService.rankCandidates(attempt.getQuestion(), dense, topK);
		checkCancellation.run();
		List<FaqSearchResponseDto> ranked = outcome.rankedCandidates();
		if (ranked == null || ranked.isEmpty()) {
			log.info("FAQ 검색 결과가 없어 미응답으로 처리합니다: 시도ID={}", attempt.getId());
			createUnansweredQuestion(attempt, UnansweredReason.NO_FAQ, null);
			return ChatAnswerResult.failed(ChatErrorCode.NO_FAQ);
		}
		List<FaqSearchResponseDto> similar = ranked.stream()
				.filter(result -> Double.isFinite(result.similarityScore())
						&& result.similarityScore() >= confidenceThreshold)
				.toList();
		if (similar.isEmpty()) {
			log.info("FAQ 질문 벡터 유사도가 기준값 미만입니다: 시도ID={}, 최고유사도={}, 기준값={}",
					attempt.getId(), highestSimilarity(ranked), confidenceThreshold);
			createUnansweredQuestion(attempt, UnansweredReason.INSUFFICIENT_FAQ, outcome.denseTop1());
			return ChatAnswerResult.failed(ChatErrorCode.INSUFFICIENT_FAQ);
		}
		List<FaqSearchResponseDto> candidates = similar.stream().limit(topK).toList();
		List<FaqSearchResponseDto> sentenceCandidates = candidates.stream()
				.filter(faq -> faq.intent() == Intent.GENERAL)
				.toList();
		log.info("FAQ 답변 문장 검색을 시작합니다: 시도ID={}, 검색후보수={}, 유사도통과수={}, 문장검사대상FAQ수={}, Reranker정렬={}, Reranker실패사유={}",
				attempt.getId(), dense.candidates().size(), similar.size(),
				sentenceCandidates.size(), outcome.reranked(), outcome.fallbackReason());
		List<MatchedFaq> matched = sentenceCandidates.isEmpty() ? List.of()
				: faqSentenceFilterService.filter(dense.queryVector(), sentenceCandidates);
		checkCancellation.run();
		log.info("FAQ 답변 문장 검색을 완료했습니다: 시도ID={}, 통과FAQ수={}, 통과FAQ ID={}",
				attempt.getId(), matched.size(), matched.stream().map(item -> item.faq().faqId()).toList());
		Map<Long, String> matchedAnswers = matched.stream()
				.collect(Collectors.toMap(item -> item.faq().faqId(), MatchedFaq::answerSentences));
		List<FaqSearchResponseDto> originalFaqs = new ArrayList<>();
		List<FaqSearchResponseDto> promptFaqs = new ArrayList<>();
		for (FaqSearchResponseDto faq : candidates) {
			boolean generalFaq = faq.intent() == Intent.GENERAL;
			String answer = generalFaq ? matchedAnswers.get(faq.faqId()) : faq.answer();
			if (generalFaq && answer == null) {
				continue;
			}
			originalFaqs.add(faq);
			promptFaqs.add(new FaqSearchResponseDto(faq.faqId(), faq.question(), answer,
					faq.similarityScore(), faq.intent(), faq.variantId(), faq.variantQuestion(), faq.cacheable()));
		}
		if (originalFaqs.isEmpty()) {
			log.info("답변 근거로 사용할 FAQ가 없어 미응답으로 처리합니다: 시도ID={}", attempt.getId());
			createUnansweredQuestion(attempt, UnansweredReason.INSUFFICIENT_FAQ, outcome.denseTop1());
			return ChatAnswerResult.failed(ChatErrorCode.INSUFFICIENT_FAQ);
		}
		AnswerProgress.report(Phase.COLLECTING_CONTEXT);
		AnswerMaterials materials = chatContextCollector.collect(
				new ChatContext(attempt.getUserId(), attempt.getQuestion(), location), promptFaqs);
		log.info("FAQ 및 조회 자료로 LLM 답변을 생성합니다: 시도ID={}, FAQ ID={}, 준비시간={}ms",
				attempt.getId(), originalFaqs.stream().map(FaqSearchResponseDto::faqId).toList(), elapsedMillis(startedAt));
		AiAnswer answer = aiService.generateAnswer(materials);
		checkCancellation.run();
		if (answer == null || !StringUtils.hasText(answer.answer())) {
			throw new LlmException(LlmErrorCode.LLM_RESPONSE_INVALID);
		}
		if (answer.abstained()) {
			createUnansweredQuestion(attempt, UnansweredReason.LLM_ABSTAIN, originalFaqs.getFirst());
		}
		return ChatAnswerResult.answered(answer, originalFaqs);
	}

	private long elapsedMillis(long startedAt) {
		return (System.nanoTime() - startedAt) / 1_000_000;
	}

	private void createUnansweredQuestion(
			AnswerAttemptsHistory attempt, UnansweredReason reason, FaqSearchResponseDto bestResult) {
		// 재검색은 사용자가 의도를 직접 고른 결과라서, FAQ 부족 신호(미응답 질문)로 쌓지 않습니다.
		if (attempt.isResearch()) {
			return;
		}
		try {
			unansweredQuestionService.createUnansweredQuestion(
					attempt.getId(),
					attempt.getQuestion(),
					reason,
					bestResult == null ? null : bestResult.faqId(),
					bestResult == null ? null : bestResult.similarityScore());
		} catch (RuntimeException exception) {
			log.warn("미응답 질문 저장에 실패했습니다: 시도ID={}", attempt.getId(), exception);
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
}
