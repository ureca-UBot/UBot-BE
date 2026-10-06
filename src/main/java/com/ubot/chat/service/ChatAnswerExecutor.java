package com.ubot.chat.service;

import com.ubot.ai.dto.AiAnswer;
import com.ubot.ai.dto.Location;
import com.ubot.chat.dto.response.ChatResponseDto;
import com.ubot.chat.dto.response.ChatStoreDto;
import com.ubot.chat.entity.AnswerAttemptsHistory;
import com.ubot.chat.exception.ChatErrorCode;
import com.ubot.chat.exception.ChatException;
import com.ubot.common.ErrorCode;
import com.ubot.common.GlobalException;
import com.ubot.faq.dto.response.FaqSearchResponseDto;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.FutureTask;
import java.util.concurrent.RejectedExecutionException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Component;

/** 답변 생성 작업을 별도 스레드에서 실행하고, 타임아웃·취소를 처리하며 성공·실패 결과를 저장해 응답을 확정합니다. */
@Slf4j
@Component
public class ChatAnswerExecutor {
	private final ChatAnswerProcessor chatAnswerProcessor;
	private final ChatAttemptsService chatAttemptsService;
	private final Executor chatExecutor;

	public ChatAnswerExecutor(
			ChatAnswerProcessor chatAnswerProcessor,
			ChatAttemptsService chatAttemptsService,
			@Qualifier("chatExecutor") Executor chatExecutor
	) {
		this.chatAnswerProcessor = chatAnswerProcessor;
		this.chatAttemptsService = chatAttemptsService;
		this.chatExecutor = chatExecutor;
	}

	public ChatAnswerTask startAnswerGeneration(AnswerAttemptsHistory attempt, Location location, String userIp) {
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
			log.debug("답변 생성 작업을 제출했습니다: 시도ID={}", attempt.getId());
		} catch (RejectedExecutionException exception) {
			log.error("답변 생성 작업 제출에 실패했습니다: 시도ID={}", attempt.getId(), exception);
			handleAnswerFailure(attempt, ChatErrorCode.TASK_START_FAILED, generation);
		}
		return generation;
	}

	private ChatResponseDto generateAnswer(
			AnswerAttemptsHistory attempt, Location location, ChatAnswerTask generation, String userIp
	) {
		long startedAt = System.nanoTime();
		try {
			log.info("답변 생성 작업을 시작했습니다: 시도ID={}, 재시도횟수={}", attempt.getId(), attempt.getAttemptCount());
			// 계산 단계 사이마다 취소를 확인하도록 확인 함수를 넘깁니다.
			ChatAnswerResult result = chatAnswerProcessor.generateAnswer(
					attempt, location, () -> checkCancellation(generation));
			if (result.isFailed()) {
				return handleAnswerFailure(attempt, result.errorCode(), generation);
			}
			AiAnswer answer = result.answer();
			List<FaqSearchResponseDto> filteredResults = result.faqs();

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

	private long elapsedMillis(long startedAt) {
		return (System.nanoTime() - startedAt) / 1_000_000;
	}
}
