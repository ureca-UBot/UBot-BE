package com.ubot.chat.service;

import com.ubot.ai.service.AiService;
import com.ubot.chat.dto.response.ChatResponseDto;
import com.ubot.chat.entity.AnswerAttemptsHistory;
import com.ubot.chat.exception.ChatErrorCode;
import com.ubot.chat.exception.ChatException;
import com.ubot.common.ErrorCode;
import com.ubot.common.GlobalException;
import com.ubot.faq.dto.response.FaqSearchResponseDto;
import com.ubot.faq.service.FaqVectorService;
import com.ubot.forbiddenword.service.ForbiddenWordFilterService;
import com.ubot.llm.dto.response.LlmResponseDto;
import com.ubot.llm.exception.LlmErrorCode;
import com.ubot.llm.exception.LlmException;
import com.ubot.prompt.exception.PromptException;
import com.ubot.unanswered.enums.UnansweredReason;
import com.ubot.unanswered.service.UnansweredQuestionService;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.FutureTask;
import java.util.concurrent.RejectedExecutionException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/** 기존 FAQ·AI 서비스를 별도 스레드에서 호출하고 최종 답변 결과를 반환합니다. */
@Slf4j
@Service
public class ChatService {
	@Value("${CHAT_TOP_K:3}")
	private int topK;

	@Value("${CHAT_CONFIDENCE_THRESHOLD:0.75}")
	private double confidenceThreshold;

	private final FaqVectorService faqVectorService;
	private final AiService aiService;
	private final ChatAttemptsService chatAttemptsService;
	private final ForbiddenWordFilterService forbiddenWordFilterService;
	private final UnansweredQuestionService unansweredQuestionService;
	private final Executor chatExecutor;

	public ChatService(
			FaqVectorService faqVectorService,
			AiService aiService,
			ChatAttemptsService chatAttemptsService,
			ForbiddenWordFilterService forbiddenWordFilterService,
			UnansweredQuestionService unansweredQuestionService,
			@Qualifier("chatExecutor") Executor chatExecutor
	) {
		this.faqVectorService = faqVectorService;
		this.aiService = aiService;
		this.chatAttemptsService = chatAttemptsService;
		this.forbiddenWordFilterService = forbiddenWordFilterService;
		this.unansweredQuestionService = unansweredQuestionService;
		this.chatExecutor = chatExecutor;
	}

	public ChatAnswerTask createChat(Long userId, String question, String userIp) {
		validateQuestion(question);

		AnswerAttemptsHistory attempt = chatAttemptsService.createAnswerAttempt(userId, question);
		log.info("회원 답변 시도를 생성했습니다: 시도ID={}, 사용자ID={}, 질문={}", attempt.getId(), userId, question);
		return startAnswerGeneration(attempt, userIp);
	}

	public ChatAnswerTask createGuestChat(Long conversationId, String question, String userIp) {
		validateQuestion(question);

		AnswerAttemptsHistory attempt = chatAttemptsService.createGuestAnswerAttempt(conversationId, question);
		log.info("비회원 답변 시도를 생성했습니다: 시도ID={}, 대화ID={}, 질문={}", attempt.getId(), conversationId, question);
		return startAnswerGeneration(attempt, userIp);
	}

	public ChatAnswerTask retryChat(Long userId, String idempotencyKey, String userIp) {
		validateIdempotencyKey(idempotencyKey);

		AnswerAttemptsHistory attempt = chatAttemptsService.createRetryAttempt(userId, idempotencyKey);
		log.info("회원 답변 재시도를 생성했습니다: 시도ID={}, 사용자ID={}, 재시도횟수={}", attempt.getId(), userId, attempt.getAttemptCount());
		return startAnswerGeneration(attempt, userIp);
	}

	public ChatAnswerTask retryGuestChat(Long conversationId, String idempotencyKey, String userIp) {
		validateIdempotencyKey(idempotencyKey);

		AnswerAttemptsHistory attempt = chatAttemptsService.createGuestRetryAttempt(conversationId, idempotencyKey);
		log.info("비회원 답변 재시도를 생성했습니다: 시도ID={}, 대화ID={}, 재시도횟수={}", attempt.getId(), conversationId, attempt.getAttemptCount());
		return startAnswerGeneration(attempt, userIp);
	}

	private void validateQuestion(String question) {
		if (!StringUtils.hasText(question) || question.length() > 4000) {
			throw new ChatException(ChatErrorCode.INVALID_CHAT_REQUEST);
		}
		// 임베딩·FAQ 검색·LLM 호출 전에 금지어를 차단합니다.
		forbiddenWordFilterService.validateForbiddenWord(question);
	}

	private void validateIdempotencyKey(String idempotencyKey) {
		if (idempotencyKey == null || !idempotencyKey.matches("[0-9a-f]{64}")) {
			throw new ChatException(ChatErrorCode.INVALID_CHAT_RETRY_REQUEST);
		}
	}

	private ChatAnswerTask startAnswerGeneration(AnswerAttemptsHistory attempt, String userIp) {
		ChatAnswerTask generation = new ChatAnswerTask(new CompletableFuture<>(), () -> {
			AnswerAttemptsHistory savedAttempt = chatAttemptsService.saveAnswerTimeout(attempt);
			return ChatResponseDto.from(savedAttempt, savedAttempt.getErrorMessage(),
					chatAttemptsService.validateRetryAttempt(savedAttempt).isEmpty());
		});
		FutureTask<Void> task = new FutureTask<>(() -> {
			try {
				generateAnswer(attempt, generation, userIp);
			} catch (Throwable exception) {
				generation.completeExceptionally(exception);
			}
			return null;
		});
		generation.setWorker(task);
		try {
			chatExecutor.execute(task);
			log.debug("답변 생성 작업을 제출했습니다: 시도ID={}", attempt.getId());
		} catch (RejectedExecutionException exception) {
			log.error("답변 생성 작업 제출에 실패했습니다: 시도ID={}", attempt.getId(), exception);
			handleAnswerFailure(attempt, ChatErrorCode.TASK_START_FAILED, generation);
		}
		return generation;
	}

	private ChatResponseDto generateAnswer(AnswerAttemptsHistory attempt, ChatAnswerTask generation, String userIp) {
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
			log.info("LLM 답변 생성을 시작합니다: 시도ID={}, 참고FAQ수={}", attempt.getId(), filteredResults.size());
			LlmResponseDto answer = aiService.generateAnswer(attempt.getQuestion(), filteredResults);
			log.info("LLM 답변 생성을 완료했습니다: 시도ID={}, 응답={}", attempt.getId(),
					answer == null || answer.answer() == null ? 0 : answer.answer());
			checkCancellation(generation);
			if (answer == null || !StringUtils.hasText(answer.answer())) {
				throw new LlmException(LlmErrorCode.LLM_RESPONSE_INVALID);
			}

			checkCancellation(generation);
			return generation.complete(() -> {
				AnswerAttemptsHistory savedAttempt = chatAttemptsService.saveAnswerSuccess(attempt, answer.answer(), filteredResults, userIp);
				if (!"SUCCESS".equals(savedAttempt.getStatus())) {
					throw createAnswerFailure(savedAttempt);
				}
				log.info("답변 생성에 성공했습니다: 시도ID={}, 처리시간={}ms", attempt.getId(), elapsedMillis(startedAt));
				// 저장 커밋과 성공 응답 확정 사이에 타임아웃이 끼어들지 않게 합니다.
				return ChatResponseDto.from(savedAttempt, answer.answer(), false);
			});
		} catch (GlobalException exception) {
			log.warn("답변 생성이 업무 예외로 종료되었습니다: 시도ID={}, 오류코드={}, 오류메시지={}",
					attempt.getId(), exception.getErrorCode().getCode(), exception.getErrorCode().getMessage());
			return handleAnswerFailure(attempt, exception.getErrorCode(), generation);
		} catch (Exception exception) {
			checkCancellation(generation);
			if (exception instanceof PromptException) {
				log.warn("답변 프롬프트를 준비하지 못했습니다: 시도ID={}", attempt.getId());
				return handleAnswerFailure(attempt, ChatErrorCode.PROMPT_NOT_READY, generation);
			}
			if (exception instanceof DataAccessException) {
				log.warn("답변 저장소 접근에 실패했습니다: 시도ID={}", attempt.getId(), exception);
				return handleAnswerFailure(attempt, ChatErrorCode.STORAGE_UNAVAILABLE, generation);
			}
			log.error("답변 생성에 실패했습니다: 시도ID={}", attempt.getId(), exception);
			return handleAnswerFailure(attempt, ChatErrorCode.INTERNAL_ERROR, generation);
		}
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

	private ChatResponseDto handleAnswerFailure(
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
