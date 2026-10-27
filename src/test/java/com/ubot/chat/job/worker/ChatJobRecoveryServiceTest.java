package com.ubot.chat.job.worker;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.ubot.chat.exception.ChatErrorCode;
import com.ubot.chat.job.entity.ChatJob;
import com.ubot.chat.job.entity.ChatJobStatus;
import com.ubot.chat.job.stream.ChatJobMessage;
import com.ubot.chat.job.stream.ReceivedChatJob;
import com.ubot.ranking.enums.RegionSido;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import com.ubot.chat.entity.AnswerAttemptsHistory;
import com.ubot.chat.job.stream.PendingChatJob;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.test.util.ReflectionTestUtils;

@DisplayName("대기 작업과 멈춘 실행 복구")
class ChatJobRecoveryServiceTest extends ChatJobTestSupport {

	@Test
	void normalAndRecoveredJobsShareOneConcurrencyLimit() throws Exception {
		List<ReceivedChatJob> messages = staleJobs(3, 1);
		when(stream.findStale(eq(RECLAIM_IDLE), anyInt())).thenReturn(List.of(
				new PendingChatJob("message-2", "dead-worker", 1),
				new PendingChatJob("message-3", "dead-worker", 1)));
		var firstStarted = new CountDownLatch(1);
		var bothStarted = new CountDownLatch(2);
		var release = new CountDownLatch(1);
		var active = new AtomicInteger();
		var maximum = new AtomicInteger();
		when(processor.process(any(), any(), any(), any())).thenAnswer(call -> {
			maximum.accumulateAndGet(active.incrementAndGet(), Math::max);
			AnswerAttemptsHistory attempt = call.getArgument(0);
			if (attempt.getId() == 1L) firstStarted.countDown();
			bothStarted.countDown();
			try { release.await(); } finally { active.decrementAndGet(); }
			return answered();
		});
		configure(2, Duration.ofSeconds(1));
		try {
			var normal = runner.submit(messages.getFirst());
			assertThat(firstStarted.await(2, TimeUnit.SECONDS)).isTrue();
			var recovered = recovery.recover();
			assertThat(bothStarted.await(2, TimeUnit.SECONDS)).isTrue();
			verify(stream).claim("test-worker", RECLAIM_IDLE, List.of("message-2"));
			verify(stream, never()).ack("message-3");
			assertThat(maximum.get()).isEqualTo(2);
			release.countDown();
			normal.get(2, TimeUnit.SECONDS);
			recovered.get(2, TimeUnit.SECONDS);
			verify(stream).ack("message-1");
			verify(stream).ack("message-2");
		} finally { release.countDown(); }
	}

	@Test
	void failedStreamClaimDoesNotPreventLaterExecution() throws Exception {
		ReceivedChatJob message = staleJobs(1, 1).getFirst();
		when(stream.claim("test-worker", RECLAIM_IDLE, List.of("message-1")))
				.thenThrow(new DataAccessResourceFailureException("redis down"));
		when(processor.process(any(), any(), any(), any())).thenReturn(answered());
		configure(1, Duration.ofSeconds(1));

		recovery.recover().get(2, TimeUnit.SECONDS);
		// 제출 자체가 슬롯을 기다리므로 별도 스레드에서 호출하고 대기 시간을 제한합니다.
		try (var submitter = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
			var result = submitter.submit(() -> runner.submit(message).get(2, TimeUnit.SECONDS));
			try { result.get(2, TimeUnit.SECONDS); } finally { result.cancel(true); }
		}
		verify(stream).ack(message.messageId());
	}

	@Test
	@DisplayName("오래 방치된 메시지는 넘겨받고, 멈춘 실행의 작업은 started_at을 새로 잡아 다시 실행한다")
	void staleMessageIsReclaimedAndStuckRunIsTakenOver() {
		ReceivedChatJob message = staleJobs(1, 1).getFirst();
		ReflectionTestUtils.setField(storedJobs.get(1L), "status", ChatJobStatus.RUNNING);
		when(queue.claim(anyLong(), any())).thenReturn(Optional.empty());
		LocalDateTime takenAt = STARTED_AT.plusMinutes(10);
		when(queue.takeOver(eq(1L), any(), any())).thenReturn(claimed(takenAt));
		when(processor.process(any(), any(), any(), any())).thenReturn(answered());
		configure(1, Duration.ofSeconds(20));

		recovery.recover();

		verify(stream, timeout(2_000)).ack(message.messageId());
		verify(processor).process(any(), any(), any(), any());
		verify(completion).complete(eq(1L), eq(takenAt), any(), eq(RegionSido.SEOUL));
		var now = ArgumentCaptor.forClass(LocalDateTime.class);
		var startedBefore = ArgumentCaptor.forClass(LocalDateTime.class);
		verify(queue).takeOver(eq(1L), now.capture(), startedBefore.capture());
		assertThat(Duration.between(startedBefore.getValue(), now.getValue()))
				.as("heartbeat가 heartbeat 기준보다 오래 끊긴 실행만 넘겨받음")
				.isEqualTo(HEARTBEAT_TIMEOUT);
	}

	@Test
	@DisplayName("heartbeat가 끊긴 실행의 메시지는 reclaimIdle을 기다리지 않고 넘겨받아 다시 실행한다")
	void silentRunIsReclaimedBeforeReclaimIdle() {
		ReceivedChatJob message = silentJobs(1).getFirst();
		ChatJob job = storedJobs.get(1L);
		ReflectionTestUtils.setField(job, "status", ChatJobStatus.RUNNING);
		ReflectionTestUtils.setField(job, "heartbeatAt", LocalDateTime.now().minusMinutes(2));
		when(queue.claim(anyLong(), any())).thenReturn(Optional.empty());
		LocalDateTime takenAt = STARTED_AT.plusMinutes(2);
		when(queue.takeOver(eq(1L), any(), any())).thenReturn(claimed(takenAt));
		when(processor.process(any(), any(), any(), any())).thenReturn(answered());
		configure(1, Duration.ofSeconds(20));

		recovery.recover();

		verify(stream).claim("test-worker", HEARTBEAT_TIMEOUT, List.of(message.messageId()));
		verify(stream, timeout(2_000)).ack(message.messageId());
		verify(completion).complete(eq(1L), eq(takenAt), any(), eq(RegionSido.SEOUL));
	}

	@Test
	@DisplayName("heartbeat가 살아 있는 실행의 메시지는 오래 ACK되지 않아도 넘겨받지 않아 전달 횟수가 늘지 않는다")
	void liveRunMessageIsNotReclaimed() {
		silentJobs(1);
		ChatJob job = storedJobs.get(1L);
		ReflectionTestUtils.setField(job, "status", ChatJobStatus.RUNNING);
		ReflectionTestUtils.setField(job, "heartbeatAt", LocalDateTime.now().minusSeconds(5));
		configure(1, Duration.ofSeconds(20));

		recovery.recover();

		verify(stream, never()).claim(eq("test-worker"), eq(HEARTBEAT_TIMEOUT), argThat(ids -> !ids.isEmpty()));
		verify(stream, never()).ack(anyString());
		verifyNoInteractions(processor);
	}

	@Test
	@DisplayName("내용을 읽지 못한 메시지는 heartbeat 단계에서 넘겨받지 않고 reclaimIdle 단계에 맡긴다")
	void unreadableIdleMessageIsLeftForReclaimIdle() {
		silentJobs(1);
		when(stream.findJobIds(anyList())).thenReturn(Map.of());
		configure(1, Duration.ofSeconds(20));

		recovery.recover();

		verify(stream, never()).claim(eq("test-worker"), eq(HEARTBEAT_TIMEOUT), argThat(ids -> !ids.isEmpty()));
		verifyNoInteractions(processor);
	}

	@Test
	@DisplayName("넘겨받은 메시지의 작업을 다른 실행이 막 시작했으면 실행하지도 ACK하지도 않고 남겨 둔다")
	void reclaimedMessageForActiveRunIsLeftPending() {
		staleJobs(1, 1);
		ReflectionTestUtils.setField(storedJobs.get(1L), "status", ChatJobStatus.RUNNING);
		when(queue.claim(anyLong(), any())).thenReturn(Optional.empty());
		when(queue.takeOver(anyLong(), any(), any())).thenReturn(Optional.empty());
		configure(1, Duration.ofSeconds(20));

		recovery.recover();

		verify(queue).takeOver(eq(1L), any(), any());
		verify(stream, never()).ack(anyString());
		verifyNoInteractions(processor);
	}

	@Test
	@DisplayName("넘겨받은 메시지의 작업이 이미 끝났으면(커밋 뒤 ACK 전에 죽음) 다시 실행하지 않고 ACK한다")
	void reclaimedMessageForFinishedJobIsAcked() {
		ReceivedChatJob message = staleJobs(1, 1).getFirst();
		ReflectionTestUtils.setField(storedJobs.get(1L), "status", ChatJobStatus.SUCCEEDED);
		configure(1, Duration.ofSeconds(20));

		recovery.recover();

		verify(stream).ack(message.messageId());
		verify(queue, never()).claim(anyLong(), any());
		verifyNoInteractions(processor);
	}

	@Test
	@DisplayName("전달 횟수를 다 쓴 메시지는 다시 실행하지 않고, 멈춘 작업을 CHAT-015로 끝낸 뒤 ACK한다")
	void exhaustedMessageFailsStuckJob() {
		ReceivedChatJob message = staleJobs(1, 3).getFirst();
		when(completion.failStuckRunning(eq(1L), any(), eq(ChatErrorCode.INTERNAL_ERROR))).thenReturn(true);
		configure(1, Duration.ofSeconds(20));

		recovery.recover();

		var order = inOrder(completion, stream);
		order.verify(completion).failStuckRunning(eq(1L), any(), eq(ChatErrorCode.INTERNAL_ERROR));
		order.verify(stream).ack(message.messageId());
		verify(queue, never()).claim(anyLong(), any());
		verifyNoInteractions(processor);
	}

	@Test
	@DisplayName("빈 슬롯 수만큼만 메시지를 넘겨받고, 나머지는 처리 대기로 남긴다")
	void reclaimsOnlyAsManyAsFreeSlots() {
		staleJobs(3, 1);
		when(processor.process(any(), any(), any(), any())).thenReturn(answered());
		configure(2, Duration.ofSeconds(20));

		recovery.recover();

		verify(stream).claim("test-worker", RECLAIM_IDLE, List.of("message-1", "message-2"));
		verify(stream, timeout(2_000).times(2)).ack(anyString());
		verify(stream, never()).ack("message-3");
	}

	@Test
	@DisplayName("정리 작업은 시작된 지 넘겨받기 기준의 두 배가 지나도록 끝나지 않은 작업을 CHAT-015로 끝낸다")
	void recoveryFailsOrphanedRunningJobs() {
		when(queue.findRunningStartedBefore(any(), anyInt())).thenReturn(List.of(31L));
		configure(1, Duration.ofSeconds(20));
		LocalDateTime before = LocalDateTime.now();

		recovery.recover();

		LocalDateTime after = LocalDateTime.now();
		var startedBefore = ArgumentCaptor.forClass(LocalDateTime.class);
		verify(queue).findRunningStartedBefore(startedBefore.capture(), anyInt());
		assertThat(startedBefore.getValue()).isBetween(
				before.minus(RECLAIM_IDLE.multipliedBy(2)).minusSeconds(1), after.minus(RECLAIM_IDLE.multipliedBy(2)));
		verify(completion).failStuckRunning(31L, startedBefore.getValue(), ChatErrorCode.INTERNAL_ERROR);
	}

	@Test
	@DisplayName("정리 단계 하나가 실패해도(Redis 장애) 나머지 단계는 진행하고, ACK된 메시지는 스트림에서 지운다")
	void recoveryContinuesAfterFailedStep() {
		when(stream.findUnpublished(any())).thenThrow(new DataAccessResourceFailureException("redis down"));
		when(stream.findStale(any(), anyInt())).thenThrow(new DataAccessResourceFailureException("redis down"));
		when(queue.findRunningStartedBefore(any(), anyInt())).thenReturn(List.of(31L));
		configure(1, Duration.ofSeconds(20));

		recovery.recover();

		verify(completion).failStuckRunning(eq(31L), any(), eq(ChatErrorCode.INTERNAL_ERROR));
		verify(stream).trimAcknowledged();
	}

	@Test
	@DisplayName("정리 작업은 대기 한도를 넘긴 작업을 CHAT-016으로 끝낸다")
	void recoveryFailsJobsPastQueueWait() {
		when(queue.findExpiredQueued(anyInt(), eq(0L))).thenReturn(List.of(12L, 13L));
		configure(1, Duration.ofSeconds(20));

		recovery.recover();

		verify(completion).failQueued(12L, ChatErrorCode.RESPONSE_TIMEOUT);
		verify(completion).failQueued(13L, ChatErrorCode.RESPONSE_TIMEOUT);
	}

	@Test
	@DisplayName("대기 한도를 넘긴 작업이 많으면 100건씩 이어서 끝내되, 한 번에 1,000건을 넘기지 않는다")
	void expiresQueuedJobsInPagesUpToLimit() {
		List<Long> firstPage = java.util.stream.LongStream.rangeClosed(1, 100).boxed().toList();
		when(queue.findExpiredQueued(100, 0L)).thenReturn(firstPage);
		when(queue.findExpiredQueued(eq(100), org.mockito.ArgumentMatchers.longThat(id -> id > 0))).thenAnswer(call -> {
			long after = call.getArgument(1);
			return java.util.stream.LongStream.rangeClosed(after + 1, after + 100).boxed().toList();
		});
		when(completion.failQueued(anyLong(), eq(ChatErrorCode.RESPONSE_TIMEOUT))).thenReturn(true);
		configure(1, Duration.ofSeconds(20));

		recovery.recover();

		verify(completion, org.mockito.Mockito.times(1000)).failQueued(anyLong(), eq(ChatErrorCode.RESPONSE_TIMEOUT));
		verify(completion).failQueued(1000L, ChatErrorCode.RESPONSE_TIMEOUT);
		verify(completion, never()).failQueued(1001L, ChatErrorCode.RESPONSE_TIMEOUT);
		verify(queue).findExpiredQueued(100, 900L);
		verify(queue, never()).findExpiredQueued(100, 1000L);
	}

	@Test
	@DisplayName("대기 한도를 넘긴 작업이 한 페이지보다 적으면 다음 페이지를 찾지 않는다")
	void stopsExpiringWhenPageIsNotFull() {
		when(queue.findExpiredQueued(100, 0L)).thenReturn(List.of(12L, 13L));
		configure(1, Duration.ofSeconds(20));

		recovery.recover();

		verify(queue, never()).findExpiredQueued(anyInt(), org.mockito.ArgumentMatchers.longThat(id -> id > 0));
	}

	@Test
	@DisplayName("정리 작업은 오래 대기 중인데 발행 표시가 없는 작업만 요청 정보 없이 다시 발행한다")
	void recoveryRepublishesOnlyUnpublishedJobs() {
		when(queue.findQueuedOlderThan(eq(Duration.ofSeconds(30)), anyInt())).thenReturn(List.of(21L, 22L, 23L));
		when(stream.findUnpublished(List.of(21L, 22L, 23L))).thenReturn(List.of(22L, 23L));
		when(jobs.findById(22L)).thenReturn(Optional.of(ChatJob.queue(22L, CONTEXT)));
		when(jobs.findById(23L)).thenReturn(Optional.of(ChatJob.queue(23L, CONTEXT)));
		when(stream.publishIfAbsent(new ChatJobMessage(22L, CONTEXT))).thenReturn(true);
		configure(1, Duration.ofSeconds(20));

		recovery.recover();

		verify(stream).publishIfAbsent(new ChatJobMessage(22L, CONTEXT));
		verify(stream, never()).publishIfAbsent(new ChatJobMessage(21L, CONTEXT));
	}

	@Test
	@DisplayName("Redis가 통째로 비워져 대기 작업이 많아도 정리 한 번에 1,000건까지 다시 발행한다")
	void recoveryRepublishesUpToThousandJobsAtOnce() {
		List<Long> waiting = java.util.stream.LongStream.rangeClosed(1, 1000).boxed().toList();
		when(queue.findQueuedOlderThan(eq(Duration.ofSeconds(30)), anyInt())).thenReturn(waiting);
		when(stream.findUnpublished(waiting)).thenReturn(waiting);
		when(jobs.findById(anyLong())).thenAnswer(call -> Optional.of(ChatJob.queue(call.getArgument(0), CONTEXT)));
		when(stream.publishIfAbsent(any())).thenReturn(true);
		configure(1, Duration.ofSeconds(20));

		recovery.recover();

		verify(stream, org.mockito.Mockito.times(1000)).publishIfAbsent(any());
	}

	@Test
	@DisplayName("다시 발행에 실패하면 발행 표시를 지워 다음 정리에서 다시 시도하게 한다")
	void failedRepublishCanBeRetriedWithoutReservation() {
		when(queue.findQueuedOlderThan(eq(Duration.ofSeconds(30)), anyInt())).thenReturn(List.of(22L));
		when(stream.findUnpublished(List.of(22L))).thenReturn(List.of(22L));
		when(jobs.findById(22L)).thenReturn(Optional.of(ChatJob.queue(22L, CONTEXT)));
		when(stream.publishIfAbsent(any())).thenThrow(new DataAccessResourceFailureException("redis down"))
				.thenReturn(true);
		configure(1, Duration.ofSeconds(20));

		recovery.recover();
		recovery.recover();
		verify(stream, org.mockito.Mockito.times(2)).publishIfAbsent(new ChatJobMessage(22L, CONTEXT));
	}

	@Test
	@DisplayName("heartbeat가 끊겨 넘겨받은 메시지는 reason=heartbeat로 센다")
	void heartbeatReclaimIsCountedByReason() {
		silentJobs(1);
		ChatJob job = storedJobs.get(1L);
		ReflectionTestUtils.setField(job, "status", ChatJobStatus.RUNNING);
		ReflectionTestUtils.setField(job, "heartbeatAt", LocalDateTime.now().minusMinutes(2));
		when(queue.claim(anyLong(), any())).thenReturn(Optional.empty());
		when(queue.takeOver(eq(1L), any(), any())).thenReturn(claimed(STARTED_AT.plusMinutes(2)));
		when(processor.process(any(), any(), any(), any())).thenReturn(answered());
		configure(1, Duration.ofSeconds(20));
		var registry = observe();

		recovery.recover();

		assertThat(registry.get("chat.worker.reclaimed").tag("reason", "heartbeat").counter().count()).isEqualTo(1);
		assertThat(registry.find("chat.worker.reclaimed").tag("reason", "idle").counter()).isNull();
	}

	@Test
	@DisplayName("ACK 없이 오래 남은 메시지를 넘겨받으면 reason=idle로 센다")
	void idleReclaimIsCountedByReason() {
		staleJobs(1, 1);
		ReflectionTestUtils.setField(storedJobs.get(1L), "status", ChatJobStatus.RUNNING);
		when(queue.claim(anyLong(), any())).thenReturn(Optional.empty());
		when(queue.takeOver(eq(1L), any(), any())).thenReturn(claimed(STARTED_AT.plusMinutes(10)));
		when(processor.process(any(), any(), any(), any())).thenReturn(answered());
		configure(1, Duration.ofSeconds(20));
		var registry = observe();

		recovery.recover();

		assertThat(registry.get("chat.worker.reclaimed").tag("reason", "idle").counter().count()).isEqualTo(1);
	}

	@Test
	@DisplayName("전달 횟수를 다 써 실패로 끝낸 작업은 terminated(reason=exhausted)로 센다")
	void exhaustedJobIsCountedAsTerminated() {
		staleJobs(1, 3);
		when(completion.failStuckRunning(eq(1L), any(), eq(ChatErrorCode.INTERNAL_ERROR))).thenReturn(true);
		configure(1, Duration.ofSeconds(20));
		var registry = observe();

		recovery.recover();

		assertThat(registry.get("chat.worker.terminated").tag("reason", "exhausted").counter().count()).isEqualTo(1);
	}

	@Test
	void republishScansBeyondFirstThousandPublishedJobs() {
		List<Long> firstPage = java.util.stream.LongStream.rangeClosed(1, 1000).boxed().toList();
		when(queue.findQueuedOlderThan(Duration.ofSeconds(30), 1000)).thenReturn(firstPage);
		when(queue.findQueuedOlderThanAfter(Duration.ofSeconds(30), 1000, 1000)).thenReturn(List.of(1001L));
		when(stream.findUnpublished(firstPage)).thenReturn(List.of());
		when(stream.findUnpublished(List.of(1001L))).thenReturn(List.of(1001L));
		when(jobs.findById(1001L)).thenReturn(Optional.of(ChatJob.queue(1001L, CONTEXT)));
		configure(1, Duration.ofSeconds(1));
		recovery.recover();
		verify(stream).publishIfAbsent(new ChatJobMessage(1001L, CONTEXT));
	}
}
