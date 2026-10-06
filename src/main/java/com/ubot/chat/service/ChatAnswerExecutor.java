package com.ubot.chat.service;

import com.ubot.ai.dto.Location;
import com.ubot.chat.dto.response.ChatResponseDto;
import com.ubot.chat.entity.AnswerAttemptsHistory;
import com.ubot.chat.exception.ChatErrorCode;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.FutureTask;
import java.util.concurrent.RejectedExecutionException;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

/** 답변 생성 작업을 별도 스레드에서 실행하고 타임아웃·취소·시작 실패를 처리합니다. */
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
				chatAnswerProcessor.generateAnswer(attempt, location, generation, userIp);
			} catch (Throwable exception) {
				generation.completeExceptionally(exception);
			}
			return null;
		});
		generation.setWorker(task);
		try {
			chatExecutor.execute(task);
		} catch (RejectedExecutionException exception) {
			chatAnswerProcessor.handleAnswerFailure(attempt, ChatErrorCode.TASK_START_FAILED, generation);
		}
		return generation;
	}
}
