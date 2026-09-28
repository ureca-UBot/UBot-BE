package com.ubot.chat.service;

import com.ubot.chat.dto.response.ChatResponseDto;
import com.ubot.chat.exception.ChatErrorCode;
import com.ubot.chat.exception.ChatException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class ChatAnswerTaskTest {
	@Test
	void successfulSaveAndResponseFinishTogetherBeforeWaitingTimeout() throws Exception {
		Runnable timeoutStorage = mock(Runnable.class);
		var task = new ChatAnswerTask(new CompletableFuture<>(), () -> {
			timeoutStorage.run();
			return new ChatResponseDto(ChatErrorCode.RESPONSE_TIMEOUT.getMessage(), "FAIL",
					"a".repeat(64), 1, true);
		});
		var saving = new CountDownLatch(1);
		var finishSave = new CountDownLatch(1);
		var timeoutRequested = new CountDownLatch(1);
		var answer = ChatResponseDto.createSuccessAnswer("확정된 답변");
		var success = CompletableFuture.runAsync(() -> task.complete(() -> {
			saving.countDown();
			await(finishSave);
			return answer;
		}));
		CompletableFuture<Void> timeout = null;
		try {
			await(saving);
			timeout = CompletableFuture.runAsync(() -> {
				timeoutRequested.countDown();
				task.timeout();
			});
			await(timeoutRequested);
			assertThat(task.result().isDone()).isFalse();
		} finally {
			finishSave.countDown();
			success.get(5, TimeUnit.SECONDS);
			if (timeout != null) timeout.get(5, TimeUnit.SECONDS);
		}
		assertThat(task.result().get(5, TimeUnit.SECONDS)).isSameAs(answer);
		verifyNoInteractions(timeoutStorage);
	}

	@Test
	void timeoutCommitAndResponsePreventLateSuccessAndFailure() throws Exception {
		var saving = new CountDownLatch(1);
		var finishSave = new CountDownLatch(1);
		Runnable timeoutStorage = mock(Runnable.class);
		doAnswer(call -> { saving.countDown(); await(finishSave); return null; }).when(timeoutStorage).run();
		var task = new ChatAnswerTask(new CompletableFuture<>(), () -> {
			timeoutStorage.run();
			return new ChatResponseDto(ChatErrorCode.RESPONSE_TIMEOUT.getMessage(), "FAIL",
					"a".repeat(64), 1, true);
		});
		@SuppressWarnings("unchecked")
		Supplier<ChatResponseDto> lateSave = mock(Supplier.class);
		var lateSaveRequested = new CountDownLatch(1);
		var timeout = CompletableFuture.runAsync(task::timeout);
		CompletableFuture<Void> success = null;
		try {
			await(saving);
			success = CompletableFuture.runAsync(() -> {
				lateSaveRequested.countDown();
				task.complete(lateSave);
			});
			await(lateSaveRequested);
			assertThat(task.result().isDone()).isFalse();
		} finally {
			finishSave.countDown();
			timeout.get(5, TimeUnit.SECONDS);
			if (success != null) success.get(5, TimeUnit.SECONDS);
		}
		task.completeExceptionally(new IllegalStateException("늦게 도착한 오류"));
		task.timeout();
		assertThatThrownBy(() -> task.result().join()).hasCauseInstanceOf(ChatException.class)
				.satisfies(exception -> assertThat(((ChatException) exception.getCause()).getErrorCode())
						.isEqualTo(ChatErrorCode.RESPONSE_TIMEOUT));
		verifyNoInteractions(lateSave);
		verify(timeoutStorage).run();
	}

	private static void await(CountDownLatch latch) {
		try {
			assertThat(latch.await(5, TimeUnit.SECONDS)).isTrue();
		} catch (InterruptedException exception) {
			Thread.currentThread().interrupt();
			throw new AssertionError(exception);
		}
	}
}
