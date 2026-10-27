package com.ubot.chat.job.worker;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.ubot.chat.entity.AnswerAttemptsHistory;
import com.ubot.chat.exception.ChatErrorCode;
import com.ubot.chat.job.stream.ReceivedChatJob;
import com.ubot.chat.service.ChatJobOutcome;
import com.ubot.ranking.enums.RegionSido;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("Worker 소비와 수명 주기")
class ChatJobWorkerTest extends ChatJobTestSupport {
	private ChatJobWorker worker;

	@AfterEach
	void stopWorker() { if (worker != null) worker.stop(); }

	private ChatJobWorker worker(int concurrency, Duration shutdownWait) {
		return worker(properties(concurrency, shutdownWait));
	}

	private ChatJobWorker worker(ChatWorkerProperties properties) {
		configure(properties);
		return new ChatJobWorker(stream, properties, runner, recovery);
	}

	@Test
	@DisplayName("동시에 실행하는 작업은 설정한 수를 넘지 않고, 나머지 메시지는 읽지 않고 남겨 둔다")
	void runsAtMostConfiguredJobsAtOnce() throws Exception {
		queueJobs(10);
		var release = new CountDownLatch(1);
		var running = new AtomicInteger();
		var maxRunning = new AtomicInteger();
		var finished = new AtomicInteger();
		when(processor.process(any(), any(), any(), any())).thenAnswer(call -> {
			maxRunning.accumulateAndGet(running.incrementAndGet(), Math::max);
			try {
				release.await(10, TimeUnit.SECONDS);
			} finally {
				running.decrementAndGet();
				finished.incrementAndGet();
			}
			return answered();
		});
		worker = worker(3, Duration.ofSeconds(20));

		worker.start();
		waitUntil(() -> running.get() == 3);
		Thread.sleep(200);
		assertThat(running.get()).as("빈 슬롯이 없으면 더 읽지 않음").isEqualTo(3);
		assertThat(unread).as("나머지는 스트림에 남음").hasSize(7);

		release.countDown();
		waitUntil(() -> finished.get() == 10);
		assertThat(maxRunning.get()).isEqualTo(3);
		verify(stream, timeout(2_000).times(10)).ack(anyString());
	}

	@Test
	@DisplayName("종료할 때 실행 중인 작업은 끝날 때까지 기다려 기록하고, 남은 메시지는 읽지 않는다")
	void stopWaitsForRunningJob() throws Exception {
		ReceivedChatJob first = queueJobs(2).getFirst();
		var entered = new CountDownLatch(1);
		var release = new CountDownLatch(1);
		when(processor.process(any(), any(), any(), any())).thenAnswer(call -> {
			entered.countDown();
			release.await(10, TimeUnit.SECONDS);
			return answered();
		});
		worker = worker(1, Duration.ofSeconds(20));
		worker.start();
		assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();

		Thread stopping = Thread.ofVirtual().start(worker::stop);
		waitUntil(() -> !worker.isRunning());
		release.countDown();
		stopping.join(10_000);

		verify(completion).complete(eq(1L), eq(STARTED_AT), any(), any());
		verify(stream).ack(first.messageId());
		verify(queue, never()).release(anyLong(), any());
		assertThat(unread).as("종료 중에는 다음 메시지를 읽지 않음").hasSize(1);
	}

	@Test
	@DisplayName("종료 대기 시간 안에 끝나지 않은 작업은 대기 상태로 돌려놓고 메시지를 다시 발행한다")
	void stopReleasesUnfinishedJobAndRepublishes() throws Exception {
		ReceivedChatJob message = queueJobs(1).getFirst();
		var entered = new CountDownLatch(1);
		when(queue.release(1L, STARTED_AT)).thenReturn(1);
		when(processor.process(any(), any(), any(), any())).thenAnswer(call -> {
			entered.countDown();
			try {
				Thread.sleep(10_000);
			} catch (InterruptedException exception) {
				Thread.currentThread().interrupt();
			}
			call.<Runnable>getArgument(2).run();
			return answered();
		});
		worker = worker(1, Duration.ofMillis(200));
		worker.start();
		assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();

		worker.stop();

		var order = inOrder(queue, stream);
		order.verify(queue).release(1L, STARTED_AT);
		order.verify(stream).publish(message.message());
		order.verify(stream).ack(message.messageId());
		verifyNoInteractions(completion);
	}

	@Test
	void singleSlotWorkerRecoversWhileWaitingForNewMessages() throws Exception {
		ReceivedChatJob stale = staleJobs(1, 1).getFirst();
		CountDownLatch read = new CountDownLatch(1);
		when(stream.read(anyString(), anyInt(), any())).thenAnswer(call -> {
			assertThat((Duration) call.getArgument(2)).as("슬롯을 잡은 읽기는 대기하지 않음").isNull();
			read.countDown();
			return List.of();
		});
		when(processor.process(any(), any(), any(), any())).thenReturn(answered());
		var properties = new ChatWorkerProperties(1, true, Duration.ofMillis(50), "test-worker",
				Duration.ofMinutes(10), Duration.ofSeconds(30), RECLAIM_IDLE, 3, Duration.ofMillis(10),
				Duration.ofHours(24), Duration.ofSeconds(180), HEARTBEAT_TIMEOUT, Duration.ofSeconds(1), Duration.ofSeconds(5));
		worker = worker(properties);
		worker.start();
		assertThat(read.await(2, TimeUnit.SECONDS)).isTrue();
		verify(stream, timeout(2_000).atLeastOnce()).ack(stale.messageId());
	}

	@Test
	void timeoutFreesSlotAndDiscardsLateAnswerEvenIfModelIgnoresInterrupt() throws Exception {
		queueJobs(2);
		claimedExecution = Duration.ofMillis(100);
		CountDownLatch release = new CountDownLatch(1);
		CountDownLatch returned = new CountDownLatch(1);
		when(processor.process(any(), any(), any(), any())).thenAnswer(call -> {
			AnswerAttemptsHistory attempt = call.getArgument(0);
			if (attempt.getId() == 1L) {
				while (release.getCount() > 0) {
					try { release.await(); } catch (InterruptedException ignored) { }
				}
				returned.countDown();
			}
			return answered();
		});
		var properties = new ChatWorkerProperties(1, true, Duration.ofMillis(10), "test-worker",
				Duration.ofMinutes(10), Duration.ofSeconds(30), RECLAIM_IDLE, 3,
				Duration.ofMinutes(1), Duration.ofHours(24), Duration.ofMillis(100),
				HEARTBEAT_TIMEOUT, Duration.ofSeconds(20), Duration.ofSeconds(5));
		worker = worker(properties);
		var registry = new io.micrometer.core.instrument.simple.SimpleMeterRegistry();
		runner.observeWith(registry);
		try {
			worker.start();
			verify(stream, timeout(3_000)).ack("message-2");
			assertThat(registry.get("chat.worker.timeouts").counter().count()).isEqualTo(1);
			assertThat(registry.get("chat.worker.completed").tag("status", "failed").tag("code", "CHAT-016")
					.counter().count()).isEqualTo(1);
			verify(completion).complete(1L, STARTED_AT,
					ChatJobOutcome.failed(ChatErrorCode.RESPONSE_TIMEOUT), RegionSido.SEOUL);
		} finally {
			release.countDown();
		}
		assertThat(returned.await(2, TimeUnit.SECONDS)).isTrue();
		verify(completion, org.mockito.Mockito.times(1)).complete(eq(1L), any(), any(), any());
	}

	@Test
	void liveExecutionRenewsLease() throws Exception {
		queueJobs(1);
		CountDownLatch release = new CountDownLatch(1);
		when(processor.process(any(), any(), any(), any())).thenAnswer(call -> { release.await(); return answered(); });
		when(queue.heartbeat(anyLong(), any(), any())).thenReturn(1);
		var properties = new ChatWorkerProperties(1, true, Duration.ofMillis(10), "test-worker",
				Duration.ofMinutes(10), Duration.ofSeconds(30), Duration.ofMillis(90), 3,
				Duration.ofMinutes(1), Duration.ofHours(24), Duration.ofSeconds(180),
				Duration.ofMillis(90), Duration.ofSeconds(20), Duration.ofSeconds(5));
		worker = worker(properties);
		try {
			worker.start();
			verify(queue, timeout(2_000).atLeastOnce()).heartbeat(eq(1L), eq(STARTED_AT), any());
		} finally { release.countDown(); }
		verify(stream, timeout(2_000)).ack("message-1");
	}

	@Test
	@DisplayName("heartbeat 갱신 행이 없으면(다른 실행이 넘겨받음) lease.lost를 세고 실행을 멈춘다")
	void lostLeaseIsCounted() throws Exception {
		queueJobs(1);
		CountDownLatch release = new CountDownLatch(1);
		when(processor.process(any(), any(), any(), any())).thenAnswer(call -> { release.await(); return answered(); });
		when(queue.heartbeat(anyLong(), any(), any())).thenReturn(0);
		var properties = new ChatWorkerProperties(1, true, Duration.ofMillis(10), "test-worker",
				Duration.ofMinutes(10), Duration.ofSeconds(30), Duration.ofMillis(90), 3,
				Duration.ofMinutes(1), Duration.ofHours(24), Duration.ofSeconds(180),
				Duration.ofMillis(90), Duration.ofSeconds(20), Duration.ofSeconds(5));
		worker = worker(properties);
		var registry = observe();
		try {
			worker.start();
			waitUntil(() -> registry.find("chat.worker.lease.lost").counter() != null);
			assertThat(registry.get("chat.worker.lease.lost").counter().count()).isGreaterThanOrEqualTo(1);
		} finally { release.countDown(); }
	}

	@Test
	void workerHealthTracksConsumerLifecycle() throws Exception {
		worker = worker(1, Duration.ofSeconds(1));
		assertThat(worker.isOperational()).isFalse();
		worker.start();
		waitUntil(worker::isOperational);
		worker.stop();
		assertThat(worker.isOperational()).isFalse();
	}

	@Test
	void userCancellationInterruptsOnlySelectedComputationAndKeepsWorkerRunning() throws Exception {
		queueJobs(2);
		var started = new CountDownLatch(2);
		var cancelledA = new CountDownLatch(1);
		var releaseB = new CountDownLatch(1);
		when(processor.process(any(), any(), any(), any())).thenAnswer(call -> {
			AnswerAttemptsHistory attempt = call.getArgument(0);
			started.countDown();
			try {
				if (attempt.getId() == 1L) new CountDownLatch(1).await();
				else releaseB.await(5, TimeUnit.SECONDS);
			} catch (InterruptedException error) {
				if (attempt.getId() == 1L) cancelledA.countDown();
				else throw new AssertionError("다른 질문이 중단됨", error);
				throw new java.util.concurrent.CancellationException();
			}
			return answered();
		});
		worker = worker(2, Duration.ofSeconds(20));
		worker.start();
		try {
			assertThat(started.await(4, TimeUnit.SECONDS)).isTrue();
			storedJobs.get(1L).fail(); // 취소 트랜잭션이 먼저 확정한 종료 상태
			runner.cancelJob(1L);
			assertThat(cancelledA.await(2, TimeUnit.SECONDS)).isTrue();
			assertThat(worker.isRunning()).isTrue();
			releaseB.countDown();
			verify(completion, timeout(2_000)).complete(eq(2L), any(), any(), any());
			verify(completion, never()).complete(eq(1L), any(), any(), any());
			verify(stream, timeout(2_000)).ack("message-1");
		} finally { releaseB.countDown(); }
	}

	@Test
	void cancellationBeforeFutureBindingIsNotLostAndNeverStartsTheModel() throws Exception {
		queueJobs(1);
		var beforeBinding = new CountDownLatch(1);
		var resume = new CountDownLatch(1);
		when(attempts.findById(1L)).thenAnswer(call -> {
			beforeBinding.countDown();
			assertThat(resume.await(5, TimeUnit.SECONDS)).isTrue();
			return Optional.of(AnswerAttemptsHistory.builder()
					.id(1L).userId(1L).question("질문").attemptCount(1).status("PENDING").build());
		});
		worker = worker(1, Duration.ofSeconds(20));
		worker.start();
		try {
			assertThat(beforeBinding.await(4, TimeUnit.SECONDS)).isTrue();
			storedJobs.get(1L).fail();
			runner.cancelJob(1L);
			resume.countDown();
			verify(stream, timeout(2_000)).ack("message-1");
			verifyNoInteractions(processor, completion);
			assertThat(worker.isRunning()).isTrue();
		} finally {
			resume.countDown();
		}
	}
}
