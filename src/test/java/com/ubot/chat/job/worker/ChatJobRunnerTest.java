package com.ubot.chat.job.worker;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.ubot.chat.dto.response.ChatStoreDto;
import com.ubot.chat.exception.ChatErrorCode;
import com.ubot.chat.job.dto.ChatJobResult;
import com.ubot.chat.job.entity.ChatJobStatus;
import com.ubot.chat.job.service.ChatJobCompletionService;
import com.ubot.chat.job.service.ChatJobCompletionService.Completion;
import com.ubot.chat.job.stream.ReceivedChatJob;
import com.ubot.chat.service.ChatJobOutcome;
import com.ubot.ranking.enums.RegionSido;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.util.ReflectionTestUtils;

@DisplayName("답변 실행과 완료")
class ChatJobRunnerTest extends ChatJobTestSupport {

	@Test
	@DisplayName("이미 끝난 작업의 메시지는 답변을 다시 만들지 않고 ACK한다")
	void finishedJobMessageIsAckedWithoutRunning() {
		ReceivedChatJob message = queueJobs(1).getFirst();
		ReflectionTestUtils.setField(storedJobs.get(1L), "status", ChatJobStatus.SUCCEEDED);
		configure(1, Duration.ofSeconds(20));

		assertThat(driver.processAvailableJobs()).isEqualTo(1);

		verify(stream).ack(message.messageId());
		verify(queue, never()).claim(anyLong(), any());
		verifyNoInteractions(processor, completion);
	}

	@Test
	@DisplayName("다른 실행이 가진 작업의 메시지(선점 실패)는 답변을 만들지 않고 ACK한다")
	void messageForJobHeldByAnotherRunIsAckedWithoutRunning() {
		ReceivedChatJob message = queueJobs(1).getFirst();
		when(queue.claim(anyLong(), any())).thenReturn(Optional.empty());
		configure(1, Duration.ofSeconds(20));

		driver.processAvailableJobs();

		verify(stream).ack(message.messageId());
		verifyNoInteractions(processor, completion);
	}

	@Test
	void progressStorageFailureDoesNotFailAnswer() {
		queueJobs(1);
		when(queue.updatePhase(anyLong(), any(), any())).thenThrow(new org.springframework.dao.DataAccessResourceFailureException("진행 저장 실패"));
		when(processor.process(any(), any(), any(), any())).thenAnswer(call -> {
			com.ubot.ai.progress.AnswerProgress.report(com.ubot.ai.progress.AnswerProgress.Phase.SEARCHING);
			return answered();
		});
		configure(1, Duration.ofSeconds(20));
		driver.processAvailableJobs();
		verify(completion).complete(eq(1L), any(), eq(answered()), any());
		verify(stream).ack("message-1");
	}

	@Test
	void executionDoesNotRestartTimeoutAfterClaimDelay() {
		queueJobs(1);
		LocalDateTime expiredStart = LocalDateTime.now().minusMinutes(10);
		when(queue.claim(anyLong(), any())).thenReturn(claimed(expiredStart));
		when(processor.process(any(), any(), any(), any())).thenReturn(answered());
		configure(1, Duration.ofSeconds(20));
		driver.processAvailableJobs();
		verify(completion).complete(1L, expiredStart,
				ChatJobOutcome.failed(ChatErrorCode.RESPONSE_TIMEOUT), RegionSido.SEOUL);
		verify(stream).ack("message-1");
	}

	@Test
	@DisplayName("대기 시간은 선점 시각이 아니라 기다리기 직전에 남은 시간으로 잡는다")
	void remainingTimeIsRecomputedRightBeforeWaiting() throws Exception {
		LocalDateTime deadline = LocalDateTime.now().plus(Duration.ofMillis(300));
		Thread.sleep(150);
		var waited = new AtomicLong(-1);
		var future = new FutureTask<>(() -> "답변") {
			@Override
			public String get(long timeout, TimeUnit unit) {
				waited.set(unit.toMillis(timeout));
				return "답변";
			}
		};

		assertThat(ChatJobRunner.awaitUntil(future, deadline)).isEqualTo("답변");
		assertThat(waited.get()).isBetween(0L, 150L);
	}

	@Test
	@DisplayName("결과가 마감을 넘겨 돌아오면 성공이 아니라 시간 초과로 본다")
	void resultReturnedAfterDeadlineIsTimeout() {
		LocalDateTime deadline = LocalDateTime.now().plus(Duration.ofMillis(50));
		// 정해진 대기 시간을 넘겨 돌아오는 GC·스케줄링 지연을 재현함
		var late = new FutureTask<>(() -> "답변") {
			@Override
			public String get(long timeout, TimeUnit unit) throws InterruptedException {
				Thread.sleep(unit.toMillis(timeout) + 50);
				return "답변";
			}
		};

		assertThatThrownBy(() -> ChatJobRunner.awaitUntil(late, deadline)).isInstanceOf(TimeoutException.class);
	}

	@Test
	void executionStopsAtStoredDeadlineEvenIfExecutionWindowIsLonger() throws Exception {
		queueJobs(1);
		// 접수 후 전체 기한이 먼저 끝나는 경우처럼, 저장된 마감이 실행 한도(180초)보다 먼저 옴
		when(queue.claim(anyLong(), any())).thenAnswer(call -> claimed(STARTED_AT, Duration.ofMillis(200)));
		var interrupted = new CountDownLatch(1);
		when(processor.process(any(), any(), any(), any())).thenAnswer(call -> {
			try { new CountDownLatch(1).await(10, TimeUnit.SECONDS); }
			catch (InterruptedException exception) { interrupted.countDown(); throw new java.util.concurrent.CancellationException(); }
			return answered();
		});
		configure(1, Duration.ofSeconds(20));
		long began = System.nanoTime();
		driver.processAvailableJobs();
		assertThat(Duration.ofNanos(System.nanoTime() - began)).isLessThan(Duration.ofSeconds(1));
		assertThat(interrupted.await(1, TimeUnit.SECONDS)).isTrue();
		verify(completion).complete(eq(1L), any(), eq(ChatJobOutcome.failed(ChatErrorCode.RESPONSE_TIMEOUT)), any());
		verify(stream).ack("message-1");
	}

	@Test
	@DisplayName("결과를 DB에 기록한 뒤에만 ACK하고, 성공 결과는 Redis에 남긴다")
	void acksOnlyAfterCompletionIsRecorded() {
		ReceivedChatJob message = queueJobs(1).getFirst();
		var store = ChatStoreDto.of(true, null);
		ChatJobOutcome outcome = new ChatJobOutcome("답변", List.of(), store, null, List.of());
		when(processor.process(any(), any(), any(), any())).thenReturn(outcome);
		when(completion.complete(1L, STARTED_AT, outcome, RegionSido.SEOUL)).thenReturn(new Completion(true, 10L));
		configure(1, Duration.ofSeconds(20));

		driver.processAvailableJobs();

		var order = inOrder(completion, results, stream);
		order.verify(completion).complete(1L, STARTED_AT, outcome, RegionSido.SEOUL);
		order.verify(results).save(eq(1L), eq(new ChatJobResult(10L, store)), eq(Duration.ofHours(24)));
		order.verify(stream).ack(message.messageId());
	}

	@Test
	@DisplayName("결과 기록이 실패하면 ACK하지 않아 메시지가 처리 대기로 남는다")
	void failedCompletionIsNotAcked() {
		queueJobs(1);
		when(processor.process(any(), any(), any(), any())).thenReturn(answered());
		when(completion.complete(anyLong(), any(), any(), any()))
				.thenThrow(new DataAccessResourceFailureException("db down"));
		configure(1, Duration.ofSeconds(20));

		assertThat(driver.processAvailableJobs()).isEqualTo(1);

		verify(stream, never()).ack(anyString());
	}

	@Test
	@DisplayName("다시 실행해도 저장할 수 없는 결과는 저장 실패로 바로 끝내고 ACK한다")
	void permanentStorageFailureIsRecordedInsteadOfRetried() {
		ReceivedChatJob message = queueJobs(1).getFirst();
		ChatJobOutcome answer = answered();
		when(processor.process(any(), any(), any(), any())).thenReturn(answer);
		when(completion.complete(1L, STARTED_AT, answer, RegionSido.SEOUL))
				.thenThrow(new DataIntegrityViolationException("fk"));
		when(completion.complete(1L, STARTED_AT, ChatJobOutcome.failed(ChatErrorCode.STORAGE_UNAVAILABLE), RegionSido.SEOUL))
				.thenReturn(new Completion(true, null));
		configure(1, Duration.ofSeconds(20));

		driver.processAvailableJobs();

		verify(completion).complete(1L, STARTED_AT, ChatJobOutcome.failed(ChatErrorCode.STORAGE_UNAVAILABLE), RegionSido.SEOUL);
		verify(stream).ack(message.messageId());
		verifyNoInteractions(results);
	}

	@Test
	@DisplayName("다른 실행이 넘겨받아 결과를 기록하지 못한 실행은 메시지를 ACK하지 않는다")
	void unrecordedCompletionIsNotAcked() {
		queueJobs(1);
		when(processor.process(any(), any(), any(), any())).thenReturn(answered());
		when(completion.complete(anyLong(), any(), any(), any())).thenReturn(new Completion(false, null));
		configure(1, Duration.ofSeconds(20));

		driver.processAvailableJobs();

		verify(stream, never()).ack(anyString());
		verifyNoInteractions(results);
	}

	@Test
	@DisplayName("선점까지 기다린 시간과 성공 결과를 코드 없이(none) 센다")
	void recordsQueueWaitAndSuccessfulCompletion() {
		queueJobs(1);
		ReflectionTestUtils.setField(storedJobs.get(1L), "createdAt", STARTED_AT.minusSeconds(3));
		when(processor.process(any(), any(), any(), any())).thenReturn(answered());
		configure(1, Duration.ofSeconds(1));
		var registry = observe();

		driver.processAvailableJobs();

		var queueWait = registry.get("chat.jobs.queue.wait").timer();
		assertThat(queueWait.count()).isEqualTo(1);
		assertThat(queueWait.totalTime(TimeUnit.SECONDS)).isEqualTo(3.0);
		assertThat(registry.get("chat.worker.completed").tag("status", "succeeded").tag("code", "none")
				.counter().count()).isEqualTo(1);
	}

	@Test
	void cacheWriteFailureStillAcknowledgesCommittedResult() {
		queueJobs(1);
		when(processor.process(any(), any(), any(), any())).thenReturn(answered());
		when(completion.complete(anyLong(), any(), any(), any())).thenReturn(new Completion(true, 10L));
		org.mockito.Mockito.doThrow(new DataAccessResourceFailureException("redis down"))
				.when(results).save(anyLong(), any(), any());
		configure(1, Duration.ofSeconds(1));
		driver.processAvailableJobs();
		verify(stream).ack("message-1");
	}

	@Test
	void cancellationCommittedAfterClaimDoesNotStartTheModel() {
		queueJobs(1);
		when(queue.claim(eq(1L), any())).thenAnswer(call -> {
			storedJobs.get(1L).fail();
			return claimed(STARTED_AT);
		});
		configure(1, Duration.ofSeconds(20));
		driver.processAvailableJobs();
		verifyNoInteractions(processor, completion);
		verify(stream).ack("message-1");
	}
}
