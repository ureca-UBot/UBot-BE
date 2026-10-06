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
import com.ubot.forbiddenword.service.ForbiddenWordFilterService;
import com.ubot.llm.exception.LlmErrorCode;
import com.ubot.llm.exception.LlmException;
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
	private final ChatContextCollector chatContextCollector;
	private final Executor chatExecutor;

	public ChatService(
			FaqVectorService faqVectorService,
			AiService aiService,
			ChatAttemptsService chatAttemptsService,
			ForbiddenWordFilterService forbiddenWordFilterService,
			UnansweredQuestionService unansweredQuestionService,
			ChatContextCollector chatContextCollector,
			@Qualifier("chatExecutor") Executor chatExecutor
	) {
		this.faqVectorService = faqVectorService;
		this.aiService = aiService;
		this.chatAttemptsService = chatAttemptsService;
		this.forbiddenWordFilterService = forbiddenWordFilterService;
		this.unansweredQuestionService = unansweredQuestionService;
		this.chatContextCollector = chatContextCollector;
		this.chatExecutor = chatExecutor;
	}

	/** 위치 없이 질문합니다. */
	public ChatAnswerTask createChat(Long userId, String question, String userIp) {
		return createChat(userId, question, null, userIp);
	}

	/** myLocation은 사용자가 내 위치 기준으로 다시 요청했을 때만 있습니다. */
	public ChatAnswerTask createChat(Long userId, String question, Location myLocation, String userIp) {
		validateQuestion(question);

		AnswerAttemptsHistory attempt = chatAttemptsService.createAnswerAttempt(userId, question);
		return startAnswerGeneration(attempt, myLocation, userIp);
	}

	/** 위치 없이 질문합니다. */
	public ChatAnswerTask createGuestChat(Long conversationId, String question, String userIp) {
		return createGuestChat(conversationId, question, null, userIp);
	}

	/** myLocation은 사용자가 내 위치 기준으로 다시 요청했을 때만 있습니다. */
	public ChatAnswerTask createGuestChat(Long conversationId, String question, Location myLocation, String userIp) {
		validateQuestion(question);

		AnswerAttemptsHistory attempt = chatAttemptsService.createGuestAnswerAttempt(conversationId, question);
		return startAnswerGeneration(attempt, myLocation, userIp);
	}

	// 시도 이력에 위치를 저장하지 않으므로 재시도는 회원·게스트 모두 위치 없이 진행합니다.
	public ChatAnswerTask retryChat(Long userId, String idempotencyKey, String userIp) {
		validateIdempotencyKey(idempotencyKey);

		AnswerAttemptsHistory attempt = chatAttemptsService.createRetryAttempt(userId, idempotencyKey);
		return startAnswerGeneration(attempt, null, userIp);
	}

	public ChatAnswerTask retryGuestChat(Long conversationId, String idempotencyKey, String userIp) {
		validateIdempotencyKey(idempotencyKey);

		AnswerAttemptsHistory attempt = chatAttemptsService.createGuestRetryAttempt(conversationId, idempotencyKey);
		return startAnswerGeneration(attempt, null, userIp);
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

	private ChatAnswerTask startAnswerGeneration(AnswerAttemptsHistory attempt, Location location, String userIp) {
		ChatAnswerTask generation = new ChatAnswerTask(new CompletableFuture<>(), () -> {
			AnswerAttemptsHistory savedAttempt = chatAttemptsService.saveAnswerTimeout(attempt);
			return ChatResponseDto.from(savedAttempt, savedAttempt.getErrorMessage(),
					chatAttemptsService.validateRetryAttempt(savedAttempt).isEmpty());
		});
		FutureTask<Void> task = new FutureTask<>(() -> {
			try {
				generateAnswer(attempt, location, generation, userIp);
			} catch (Throwable exception) {
				generation.completeExceptionally(exception);
			}
			return null;
		});
		generation.setWorker(task);
		try {
			chatExecutor.execute(task);
		} catch (RejectedExecutionException exception) {
			handleAnswerFailure(attempt, ChatErrorCode.TASK_START_FAILED, generation);
		}
		return generation;
	}

	private ChatResponseDto generateAnswer(
			AnswerAttemptsHistory attempt, Location location, ChatAnswerTask generation, String userIp
	) {
		try {
			checkCancellation(generation);
			// 기존 검색 → 유사도 판정 → AiService 흐름을 재사용합니다.
			List<FaqSearchResponseDto> results;
			try {
				results = faqVectorService.getSimilarList(attempt.getQuestion(), topK);
			} catch (DataAccessException exception) {
				return handleAnswerFailure(attempt, ChatErrorCode.VECTOR_SEARCH_FAILED, generation);
			}
			checkCancellation(generation);
			if (results == null || results.isEmpty()) {
				createUnansweredQuestion(attempt, UnansweredReason.NO_FAQ, null);
				return handleAnswerFailure(attempt, ChatErrorCode.NO_FAQ, generation);
			}
			List<FaqSearchResponseDto> filteredResults = results.stream()
					.filter(result -> Double.isFinite(result.similarityScore())
							&& result.similarityScore() >= confidenceThreshold)
					.toList();
			if (filteredResults.isEmpty()) {
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
				// 저장 커밋과 성공 응답 확정 사이에 타임아웃이 끼어들지 않게 합니다.
				return ChatResponseDto.from(savedAttempt, answer.answer(), false,
						ChatStoreDto.of(answer.locationRequired(), answer.storeMap()));
			});
		} catch (GlobalException exception) {
			return handleAnswerFailure(attempt, exception.getErrorCode(), generation);
		} catch (Exception exception) {
			checkCancellation(generation);
			if (exception instanceof DataAccessException) {
				return handleAnswerFailure(attempt, ChatErrorCode.STORAGE_UNAVAILABLE, generation);
			}
			log.error("답변 생성 실패: attemptId={}", attempt.getId(), exception);
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
			log.warn("미응답 질문 저장 실패: attemptId={}", attempt.getId(), exception);
		}
	}

	private void checkCancellation(ChatAnswerTask generation) {
		// 외부 호출이 인터럽트를 소비해도 취소 상태는 유지됩니다.
		if (generation.isCancellationRequested() || Thread.currentThread().isInterrupted()) {
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
				log.error("실패 기록 저장 실패: attemptId={}", attempt.getId(), exception);
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
}
