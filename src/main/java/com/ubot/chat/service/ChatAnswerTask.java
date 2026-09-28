package com.ubot.chat.service;

import com.ubot.chat.dto.response.ChatResponseDto;
import com.ubot.chat.exception.ChatErrorCode;
import com.ubot.chat.exception.ChatException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Future;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;

/** 답변 저장과 타임아웃 중 먼저 확정된 결과를 유지합니다. */
public final class ChatAnswerTask {
	private final CompletableFuture<ChatResponseDto> result;
	private final Supplier<ChatResponseDto> timeoutAction;
	private final ReentrantLock completionLock = new ReentrantLock();
	private volatile boolean cancellationRequested;
	private volatile Future<?> worker;

	public ChatAnswerTask(CompletableFuture<ChatResponseDto> result, Supplier<ChatResponseDto> timeoutAction) {
		this.result = result;
		this.timeoutAction = timeoutAction;
		result.whenComplete((response, exception) -> {
			if (result.isCancelled()) {
				cancellationRequested = true;
				cancelWorker();
			}
		});
	}

	public CompletableFuture<ChatResponseDto> result() {
		return result;
	}

	void setWorker(Future<?> worker) {
		this.worker = worker;
		if (isCancellationRequested()) {
			cancelWorker();
		}
	}

	boolean isCancellationRequested() {
		return cancellationRequested || result.isCancelled();
	}

	ChatResponseDto complete(Supplier<ChatResponseDto> saveResult) {
		completionLock.lock();
		try {
			if (result.isDone() || isCancellationRequested()) {
				return null;
			}
			// 외부 모델 호출은 잠그지 않고, 저장 커밋부터 응답 확정까지만 보호합니다.
			ChatResponseDto response = saveResult.get();
			result.complete(response);
			return response;
		} catch (ChatException exception) {
			// 실패 저장 후의 오류 응답도 같은 잠금 안에서 확정합니다.
			result.completeExceptionally(exception);
			return null;
		} finally {
			completionLock.unlock();
		}
	}

	void completeExceptionally(Throwable exception) {
		completionLock.lock();
		try {
			if (!result.isDone() && !isCancellationRequested()) {
				result.completeExceptionally(exception);
			}
		} finally {
			completionLock.unlock();
		}
	}

	public void timeout() {
		completionLock.lock();
		try {
			if (result.isDone()) {
				return;
			}
			cancellationRequested = true;
			cancelWorker();
			try {
				ChatResponseDto response = timeoutAction.get();
				result.completeExceptionally(new ChatException(ChatErrorCode.RESPONSE_TIMEOUT, response));
			} catch (RuntimeException exception) {
				result.completeExceptionally(exception);
			}
		} finally {
			completionLock.unlock();
		}
	}

	private void cancelWorker() {
		Future<?> currentWorker = worker;
		if (currentWorker != null) {
			currentWorker.cancel(true);
		}
	}
}
