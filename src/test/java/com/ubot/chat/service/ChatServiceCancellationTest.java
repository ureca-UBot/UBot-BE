package com.ubot.chat.service;

import com.ubot.ai.dto.AiAnswer;
import com.ubot.ai.service.AiService;
import com.ubot.chat.dto.response.ChatResponseDto;
import com.ubot.chat.entity.AnswerAttemptsHistory;
import com.ubot.chat.exception.ChatErrorCode;
import com.ubot.chat.exception.ChatException;
import com.ubot.faq.dto.response.FaqSearchResponseDto;
import com.ubot.faq.service.FaqVectorService;
import com.ubot.forbiddenword.service.ForbiddenWordFilterService;
import com.ubot.unanswered.service.UnansweredQuestionService;
import com.ubot.llm.exception.LlmErrorCode;
import com.ubot.llm.exception.LlmException;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ChatServiceCancellationTest {
	private final FaqVectorService vector = mock(FaqVectorService.class);
	private final AiService ai = mock(AiService.class);
	private final ChatAttemptsService attempts = mock(ChatAttemptsService.class);
	private final Deque<Runnable> jobs = new ArrayDeque<>();
	private final List<FaqSearchResponseDto> sources = List.of(new FaqSearchResponseDto(1L, "q", "a", 0.9));
	private final AnswerAttemptsHistory attempt = AnswerAttemptsHistory.builder()
			.id(1L).userId(1L).question("질문").idempotencyKey("a".repeat(64))
			.attemptCount(1).status("PENDING").build();
	private ChatService service;

	@BeforeEach
	void setup() {
		service = new ChatService(vector, ai, attempts, mock(ForbiddenWordFilterService.class), mock(UnansweredQuestionService.class), ChatTestFixtures.collector(), jobs::add);
		ReflectionTestUtils.setField(service, "topK", 3);
		ReflectionTestUtils.setField(service, "confidenceThreshold", 0.75);
		when(attempts.createAnswerAttempt(1L, "질문")).thenReturn(attempt);
		when(attempts.createRetryAttempt(1L, attempt.getIdempotencyKey())).thenReturn(attempt);
		when(vector.getSimilarList("질문", 3)).thenReturn(sources);
		when(ai.generateAnswer(ChatTestFixtures.materialsFor("질문", sources))).thenReturn(new AiAnswer("답변", null));
		when(attempts.saveAnswerSuccess(any(), anyString(), anyList())).thenAnswer(call -> {
			AnswerAttemptsHistory saved = call.getArgument(0);
			saved.succeed();
			return saved;
		});
	}

	@ParameterizedTest
	@ValueSource(booleans = {false, true})
	void cancellationBeforeExecutionSkipsQuestionAndRetryWork(boolean retry) {
		var answer = (retry ? service.retryChat(1L, attempt.getIdempotencyKey()) : service.createChat(1L, "질문")).result();

		assertThat(answer.cancel(true)).isTrue();
		jobs.remove().run();

		assertThat(answer.isCancelled()).isTrue();
		verifyNoInteractions(vector, ai);
		verifyNoResultSaved();
	}

	@ParameterizedTest
	@ValueSource(booleans = {false, true})
	void cancellationDuringSearchInterruptsWorkerAndSkipsLlm(boolean failAfterInterrupt) throws Exception {
		var call = new BlockingCall();
		when(vector.getSimilarList("질문", 3)).thenAnswer(invocation -> {
			call.awaitRelease();
			if (failAfterInterrupt) {
				throw new DataAccessResourceFailureException("검색 호출 중단");
			}
			return sources;
		});

		runAndCancel(service.createChat(1L, "질문").result(), call);

		verifyNoInteractions(ai);
		verifyNoResultSaved();
	}

	@ParameterizedTest
	@ValueSource(booleans = {false, true})
	void cancellationDuringLlmInterruptsWorkerAndDiscardsLateOutcome(boolean failAfterInterrupt) throws Exception {
		var call = new BlockingCall();
		when(ai.generateAnswer(ChatTestFixtures.materialsFor("질문", sources))).thenAnswer(invocation -> {
			call.awaitRelease();
			if (failAfterInterrupt) {
				throw new LlmException(LlmErrorCode.LLM_TIMEOUT);
			}
			return new AiAnswer("취소 후 늦게 도착한 답변", null);
		});

		runAndCancel(service.createChat(1L, "질문").result(), call);

		verify(ai).generateAnswer(ChatTestFixtures.materialsFor("질문", sources));
		verifyNoResultSaved();
	}

	@Test
	void cancellingOneAnswerLeavesAnotherAnswerRunning() {
		var otherAttempt = AnswerAttemptsHistory.builder()
				.id(2L).userId(2L).question("질문").idempotencyKey("b".repeat(64))
				.attemptCount(1).status("PENDING").build();
		when(attempts.createAnswerAttempt(2L, "질문")).thenReturn(otherAttempt);
		var cancelled = service.createChat(1L, "질문").result();
		var other = service.createChat(2L, "질문").result();

		cancelled.cancel(true);
		jobs.remove().run();
		jobs.remove().run();

		assertThat(cancelled.isCancelled()).isTrue();
		assertThat(other.join().status()).isEqualTo("SUCCESS");
		assertThat(other.cancel(true)).isFalse();
		verify(vector).getSimilarList("질문", 3);
		verify(ai).generateAnswer(ChatTestFixtures.materialsFor("질문", sources));
		verify(attempts).saveAnswerSuccess(otherAttempt, "답변", sources);
		verify(attempts, never()).saveAnswerSuccess(eq(attempt), anyString(), anyList());
		verify(attempts, never()).saveAnswerFailure(any(), any());
	}

	@Test
	void timeoutCancelsWorkBeforeSavingItsFailure() {
		var task = service.createChat(1L, "질문");
		doAnswer(call -> {
			assertThat(task.isCancellationRequested()).isTrue();
			attempt.fail(ChatErrorCode.RESPONSE_TIMEOUT);
			return attempt;
		}).when(attempts).saveAnswerTimeout(attempt);
		when(attempts.validateRetryAttempt(attempt)).thenReturn(Optional.empty());

		task.timeout();
		jobs.remove().run();

		assertThatThrownBy(() -> task.result().join())
				.hasCauseInstanceOf(ChatException.class)
				.satisfies(exception -> {
					var response = ((ChatException) exception.getCause()).getResponse();
					assertThat(response.idempotencyKey()).isEqualTo(attempt.getIdempotencyKey());
					assertThat(response.attemptCount()).isEqualTo(1);
					assertThat(response.retryable()).isTrue();
				});
		verify(attempts).saveAnswerTimeout(attempt);
		verifyNoInteractions(vector, ai);
		verifyNoResultSaved();
	}

	@Test
	void timeoutStorageFailureCompletesResultWithErrorAfterCancellingWork() {
		var task = service.createChat(1L, "질문");
		var failure = new DataAccessResourceFailureException("database secret");
		doThrow(failure).when(attempts).saveAnswerTimeout(attempt);

		task.timeout();
		assertThatThrownBy(() -> task.result().join()).hasCause(failure);
		assertThat(task.isCancellationRequested()).isTrue();
		jobs.remove().run();

		verifyNoInteractions(vector, ai);
		verifyNoResultSaved();
	}

	private void runAndCancel(CompletableFuture<ChatResponseDto> answer, BlockingCall call) throws Exception {
		Thread worker = Thread.ofVirtual().start(jobs.remove());
		try {
			assertThat(call.entered.await(5, TimeUnit.SECONDS)).as("외부 호출 진입").isTrue();
			assertThat(answer.cancel(true)).isTrue();
			assertThat(call.interrupted.await(5, TimeUnit.SECONDS)).as("작업 스레드에 인터럽트 전달").isTrue();
		} finally {
			call.release.countDown();
			worker.join(5_000L);
			if (worker.isAlive()) {
				worker.interrupt();
			}
		}
		assertThat(worker.isAlive()).as("취소한 작업 종료").isFalse();
		assertThat(answer.isCancelled()).isTrue();
	}

	private void verifyNoResultSaved() {
		verify(attempts, never()).saveAnswerSuccess(any(), anyString(), anyList());
		verify(attempts, never()).saveAnswerFailure(any(), any());
	}

	private static class BlockingCall {
		private final CountDownLatch entered = new CountDownLatch(1);
		private final CountDownLatch interrupted = new CountDownLatch(1);
		private final CountDownLatch release = new CountDownLatch(1);

		private void awaitRelease() throws InterruptedException {
			entered.countDown();
			try {
				assertThat(release.await(5, TimeUnit.SECONDS)).as("외부 호출 해제").isTrue();
			} catch (InterruptedException exception) {
				interrupted.countDown();
				// 외부 호출이 인터럽트를 소비하고 나중에 결과나 오류를 반환하는 상황입니다.
				assertThat(release.await(5, TimeUnit.SECONDS)).as("인터럽트 이후 외부 호출 해제").isTrue();
			}
		}
	}
}
