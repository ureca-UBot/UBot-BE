package com.ubot.chat.service;

import com.ubot.ai.dto.AiAnswer;
import com.ubot.ai.dto.AnswerMaterials;
import com.ubot.ai.dto.Location;
import com.ubot.ai.service.AiService;
import com.ubot.chat.context.ChatContext;
import com.ubot.chat.context.ChatContextCollector;
import com.ubot.chat.dto.response.ChatResponseDto;
import com.ubot.chat.dto.response.ChatStoreDto;
import com.ubot.chat.entity.AnswerAttemptsHistory;
import com.ubot.chat.exception.ChatErrorCode;
import com.ubot.chat.exception.ChatException;
import com.ubot.common.ErrorCode;
import com.ubot.common.GlobalException;
import com.ubot.faq.dto.response.FaqSearchResponseDto;
import com.ubot.faq.service.FaqVectorService;
import com.ubot.llm.exception.LlmErrorCode;
import com.ubot.llm.exception.LlmException;
import com.ubot.unanswered.enums.UnansweredReason;
import com.ubot.unanswered.service.UnansweredQuestionService;
import java.util.List;
import java.util.concurrent.CancellationException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/** FAQ 검색·유사도 판정·intent별 자료 수집·AI 호출로 답변을 만들고 결과를 시도 기록에 반영합니다. */
@Slf4j
@Component
public class ChatAnswerProcessor {
	@Value("${CHAT_TOP_K:3}")
	private int topK;

	@Value("${CHAT_CONFIDENCE_THRESHOLD:0.75}")
	private double confidenceThreshold;

	private final FaqVectorService faqVectorService;
	private final AiService aiService;
	private final ChatAttemptsService chatAttemptsService;
	private final UnansweredQuestionService unansweredQuestionService;
	private final ChatContextCollector chatContextCollector;

	public ChatAnswerProcessor(
			FaqVectorService faqVectorService,
			AiService aiService,
			ChatAttemptsService chatAttemptsService,
			UnansweredQuestionService unansweredQuestionService,
			ChatContextCollector chatContextCollector
	) {
		this.faqVectorService = faqVectorService;
		this.aiService = aiService;
		this.chatAttemptsService = chatAttemptsService;
		this.unansweredQuestionService = unansweredQuestionService;
		this.chatContextCollector = chatContextCollector;
	}

	ChatResponseDto generateAnswer(
			AnswerAttemptsHistory attempt, Location location, ChatAnswerTask generation, String userIp
	) {
		long startedAt = System.nanoTime();
		try {
			log.info("답변 생성 작업을 시작했습니다: 시도ID={}, 재시도횟수={}", attempt.getId(), attempt.getAttemptCount());
			checkCancellation(generation);
			// 기존 검색 → 유사도 판정 → AiService 흐름을 재사용합니다.
			List<FaqSearchResponseDto> results;
			try {
				results = faqVectorService.getSimilarList(attempt.getQuestion(), topK);
			} catch (DataAccessException exception) {
				return handleAnswerFailure(attempt, ChatErrorCode.VECTOR_SEARCH_FAILED, generation);
			}
			checkCancellation(generation);
			log.info("FAQ 유사도 검색을 완료했습니다: 시도ID={}, 결과수={}, 최고유사도={}, 검색결과=[{}]",
					attempt.getId(), results == null ? 0 : results.size(), highestSimilarity(results),
					formatSearchResults(results));
			if (results == null || results.isEmpty()) {
				log.info("FAQ 검색 결과가 없어 미응답으로 처리합니다: 시도ID={}", attempt.getId());
				createUnansweredQuestion(attempt, UnansweredReason.NO_FAQ, null);
				return handleAnswerFailure(attempt, ChatErrorCode.NO_FAQ, generation);
			}
			List<FaqSearchResponseDto> filteredResults = results.stream()
					.filter(result -> Double.isFinite(result.similarityScore())
							&& result.similarityScore() >= confidenceThreshold)
					.toList();
			if (filteredResults.isEmpty()) {
				log.info("FAQ 유사도가 기준값 미만입니다: 시도ID={}, 최고유사도={}, 기준값={}",
						attempt.getId(), highestSimilarity(results), confidenceThreshold);
				createUnansweredQuestion(attempt, UnansweredReason.INSUFFICIENT_FAQ, results.get(0));
				return handleAnswerFailure(attempt, ChatErrorCode.INSUFFICIENT_FAQ, generation);
			}
			checkCancellation(generation);
			// 검색된 FAQ의 intent별로 답변 자료를 모은 뒤 LLM을 한 번 호출합니다.
			AnswerMaterials materials = chatContextCollector.collect(
					new ChatContext(attempt.getUserId(), attempt.getQuestion(), location), filteredResults);
			AiAnswer answer = aiService.generateAnswer(materials);
			checkCancellation(generation);
			if (answer == null || !StringUtils.hasText(answer.answer())) {
				throw new LlmException(LlmErrorCode.LLM_RESPONSE_INVALID);
			}

			checkCancellation(generation);
			return generation.complete(() -> {
				AnswerAttemptsHistory savedAttempt =
						chatAttemptsService.saveAnswerSuccess(attempt, answer.answer(), filteredResults, userIp);
				if (!"SUCCESS".equals(savedAttempt.getStatus())) {
					throw createAnswerFailure(savedAttempt);
				}
				log.info("답변 생성에 성공했습니다: 시도ID={}, 처리시간={}ms", attempt.getId(), elapsedMillis(startedAt));
				// 저장 커밋과 성공 응답 확정 사이에 타임아웃이 끼어들지 않게 합니다.
				return ChatResponseDto.from(savedAttempt, answer.answer(), false,
						ChatStoreDto.of(answer.locationRequired(), answer.storeMap()));
			});
		} catch (GlobalException exception) {
			log.warn("답변 생성이 업무 예외로 종료되었습니다: 시도ID={}, 오류코드={}, 오류메시지={}",
					attempt.getId(), exception.getErrorCode().getCode(), exception.getErrorCode().getMessage());
			return handleAnswerFailure(attempt, exception.getErrorCode(), generation);
		} catch (Exception exception) {
			checkCancellation(generation);
			if (exception instanceof DataAccessException) {
				log.warn("답변 저장소 접근에 실패했습니다: 시도ID={}", attempt.getId(), exception);
				return handleAnswerFailure(attempt, ChatErrorCode.STORAGE_UNAVAILABLE, generation);
			}
			log.error("답변 생성에 실패했습니다: 시도ID={}", attempt.getId(), exception);
			return handleAnswerFailure(attempt, ChatErrorCode.INTERNAL_ERROR, generation);
		}
	}

	ChatResponseDto handleAnswerFailure(
			AnswerAttemptsHistory attempt, ErrorCode errorCode, ChatAnswerTask generation
	) {
		checkCancellation(generation);
		return generation.complete(() -> {
			AnswerAttemptsHistory savedAttempt;
			try {
				savedAttempt = chatAttemptsService.saveAnswerFailure(attempt, errorCode);
			} catch (RuntimeException exception) {
				checkCancellation(generation);
				// 실패 기록 자체를 저장하지 못했으면 정상 저장으로 알리지 않습니다.
				log.error("실패 이력 저장에 실패했습니다: 시도ID={}", attempt.getId(), exception);
				throw new ChatException(ChatErrorCode.STORAGE_UNAVAILABLE, new ChatResponseDto(
						ChatErrorCode.STORAGE_UNAVAILABLE.getMessage(), "FAIL",
						attempt.getIdempotencyKey(), attempt.getAttemptCount(), false
				));
			}
			throw createAnswerFailure(savedAttempt);
		});
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

	private void checkCancellation(ChatAnswerTask generation) {
		// 외부 호출이 인터럽트를 소비해도 취소 상태는 유지됩니다.
		if (generation.isCancellationRequested() || Thread.currentThread().isInterrupted()) {
			log.info("답변 생성 작업이 취소되었습니다.");
			throw new CancellationException("답변 생성 작업이 취소되었습니다.");
		}
	}

	private ChatException createAnswerFailure(AnswerAttemptsHistory attempt) {
		ErrorCode errorCode = attempt.getErrorCode() == null ? ChatErrorCode.INTERNAL_ERROR : attempt.getErrorCode();
		ChatResponseDto response = ChatResponseDto.from(attempt, errorCode.getMessage(),
				chatAttemptsService.validateRetryAttempt(attempt).isEmpty());
		return new ChatException(errorCode, response);
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

	private long elapsedMillis(long startedAt) {
		return (System.nanoTime() - startedAt) / 1_000_000;
	}
}
