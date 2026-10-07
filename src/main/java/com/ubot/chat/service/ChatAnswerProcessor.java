package com.ubot.chat.service;

import com.ubot.ai.dto.AiAnswer;
import com.ubot.ai.dto.AnswerMaterials;
import com.ubot.ai.dto.Location;
import com.ubot.ai.service.AiService;
import com.ubot.chat.context.ChatContext;
import com.ubot.chat.context.ChatContextCollector;
import com.ubot.chat.entity.AnswerAttemptsHistory;
import com.ubot.chat.exception.ChatErrorCode;
import com.ubot.faq.dto.response.FaqSearchResponseDto;
import com.ubot.faq.service.FaqVectorService;
import com.ubot.llm.exception.LlmErrorCode;
import com.ubot.llm.exception.LlmException;
import com.ubot.unanswered.enums.UnansweredReason;
import com.ubot.unanswered.service.UnansweredQuestionService;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/** FAQ 검색·유사도 판정·미응답 처리·intent별 자료 수집·AI 호출로 답변을 계산합니다. 결과 저장과 응답 확정은 ChatAnswerExecutor가 합니다. */
@Slf4j
@Component
public class ChatAnswerProcessor {
	@Value("${CHAT_TOP_K:3}")
	private int topK;

	@Value("${CHAT_CONFIDENCE_THRESHOLD:0.75}")
	private double confidenceThreshold;

	private final FaqVectorService faqVectorService;
	private final AiService aiService;
	private final UnansweredQuestionService unansweredQuestionService;
	private final ChatContextCollector chatContextCollector;

	public ChatAnswerProcessor(
			FaqVectorService faqVectorService,
			AiService aiService,
			UnansweredQuestionService unansweredQuestionService,
			ChatContextCollector chatContextCollector
	) {
		this.faqVectorService = faqVectorService;
		this.aiService = aiService;
		this.unansweredQuestionService = unansweredQuestionService;
		this.chatContextCollector = chatContextCollector;
	}

	/** checkCancellation은 단계 사이마다 호출하며, 취소되었으면 예외를 던집니다. */
	ChatAnswerResult generateAnswer(AnswerAttemptsHistory attempt, Location location, Runnable checkCancellation) {
		checkCancellation.run();
		// 기존 검색 → 유사도 판정 → AiService 흐름을 재사용합니다.
		List<FaqSearchResponseDto> results;
		try {
			results = faqVectorService.getSimilarList(attempt.getQuestion(), topK);
		} catch (DataAccessException exception) {
			return ChatAnswerResult.failed(ChatErrorCode.VECTOR_SEARCH_FAILED);
		}
		checkCancellation.run();
		log.info("FAQ 유사도 검색을 완료했습니다: 시도ID={}, 결과수={}, 최고유사도={}, 검색결과=[{}]",
				attempt.getId(), results == null ? 0 : results.size(), highestSimilarity(results),
				formatSearchResults(results));
		if (results == null || results.isEmpty()) {
			log.info("FAQ 검색 결과가 없어 미응답으로 처리합니다: 시도ID={}", attempt.getId());
			createUnansweredQuestion(attempt, UnansweredReason.NO_FAQ, null);
			return ChatAnswerResult.failed(ChatErrorCode.NO_FAQ);
		}
		List<FaqSearchResponseDto> filteredResults = results.stream()
				.filter(result -> Double.isFinite(result.similarityScore())
						&& result.similarityScore() >= confidenceThreshold)
				.toList();
		if (filteredResults.isEmpty()) {
			log.info("FAQ 유사도가 기준값 미만입니다: 시도ID={}, 최고유사도={}, 기준값={}",
					attempt.getId(), highestSimilarity(results), confidenceThreshold);
			createUnansweredQuestion(attempt, UnansweredReason.INSUFFICIENT_FAQ, results.get(0));
			return ChatAnswerResult.failed(ChatErrorCode.INSUFFICIENT_FAQ);
		}
		checkCancellation.run();
		// 검색된 FAQ의 intent별로 답변 자료를 모은 뒤 LLM을 한 번 호출합니다.
		AnswerMaterials materials = chatContextCollector.collect(
				new ChatContext(attempt.getUserId(), attempt.getQuestion(), location), filteredResults);
		AiAnswer answer = aiService.generateAnswer(materials);
		checkCancellation.run();
		if (answer == null || !StringUtils.hasText(answer.answer())) {
			throw new LlmException(LlmErrorCode.LLM_RESPONSE_INVALID);
		}
		return ChatAnswerResult.answered(answer, filteredResults);
	}

	private void createUnansweredQuestion(
			AnswerAttemptsHistory attempt, UnansweredReason reason, FaqSearchResponseDto bestResult
	) {
		try {
			unansweredQuestionService.createUnansweredQuestion(
					attempt.getId(),
					attempt.getQuestion(),
					reason,
					bestResult == null ? null : bestResult.faqId(),
					bestResult == null ? null : bestResult.similarityScore()
			);
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
						+ ", 유사도=" + result.similarityScore())
				.collect(java.util.stream.Collectors.joining(" | "));
	}

	private String normalizeForLog(String value) {
		return value == null ? "없음" : value.replaceAll("[\\r\\n\\t]+", " ");
	}
}
