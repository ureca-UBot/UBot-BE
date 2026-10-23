package com.ubot.chat.job.worker;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.ubot.chat.dto.response.ChatStoreDto;
import com.ubot.chat.entity.AnswerAttemptsHistory;
import com.ubot.chat.exception.ChatErrorCode;
import com.ubot.chat.job.dto.ChatJobResult;
import com.ubot.chat.job.dto.ChatRequestContext;
import com.ubot.chat.job.entity.ChatJob;
import com.ubot.chat.job.entity.ChatJobStatus;
import com.ubot.chat.job.repository.ChatJobQueueRepository;
import com.ubot.chat.job.repository.ChatJobQueueRepository.Claim;
import com.ubot.chat.job.repository.ChatJobRepository;
import com.ubot.chat.job.service.ChatJobCompletionService;
import com.ubot.chat.job.service.ChatJobCompletionService.Completion;
import com.ubot.chat.job.stream.ChatJobMessage;
import com.ubot.chat.job.stream.ChatJobResultStore;
import com.ubot.chat.job.stream.ChatJobStream;
import com.ubot.chat.job.stream.PendingChatJob;
import com.ubot.chat.job.stream.ReceivedChatJob;
import com.ubot.chat.repository.AnswerAttemptsHistoryRepository;
import com.ubot.chat.service.ChatJobOutcome;
import com.ubot.chat.service.ChatJobProcessor;
import com.ubot.ranking.enums.RegionSido;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.util.ReflectionTestUtils;

@DisplayName("답변 Worker 테스트")
class ChatJobWorkerTest {
	private LocalDateTime STARTED_AT = LocalDateTime.now();
	private static final ChatRequestContext CONTEXT = new ChatRequestContext(null, null, RegionSido.SEOUL);
	private static final Duration RECLAIM_IDLE = Duration.ofMinutes(5);
	// RECLAIM_IDLE만 주는 생성자의 기본 heartbeat 기준
	private static final Duration HEARTBEAT_TIMEOUT = Duration.ofMinutes(1);

	private final ChatJobStream stream = mock(ChatJobStream.class);
	private final ChatJobResultStore results = mock(ChatJobResultStore.class);
	private final ChatJobQueueRepository queue = mock(ChatJobQueueRepository.class);
	private final ChatJobRepository jobs = mock(ChatJobRepository.class);
	private final ChatJobCompletionService completion = mock(ChatJobCompletionService.class);
	private final ChatJobProcessor processor = mock(ChatJobProcessor.class);
	private final AnswerAttemptsHistoryRepository attempts = mock(AnswerAttemptsHistoryRepository.class);
	private final Deque<ReceivedChatJob> unread = new ArrayDeque<>();
	private final Map<Long, ChatJob> storedJobs = new HashMap<>();
	// 선점 SQL이 deadline_at을 잡을 때 쓰는 실행 한도. 기본 선점 mock이 이 값으로 마감을 돌려줌
	private Duration claimedExecution = Duration.ofSeconds(180);
	private ChatJobWorker worker;

	@AfterEach
	void stopWorker() {
		if (worker != null && worker.isRunning()) {
			worker.stop();
		}
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
	@DisplayName("이미 끝난 작업의 메시지는 답변을 다시 만들지 않고 ACK한다")
	void finishedJobMessageIsAckedWithoutRunning() {
		ReceivedChatJob message = queueJobs(1).getFirst();
		ReflectionTestUtils.setField(storedJobs.get(1L), "status", ChatJobStatus.SUCCEEDED);
		worker = worker(1, Duration.ofSeconds(20));

		assertThat(worker.processAvailableJobs()).isEqualTo(1);

		verify(stream).ack(message.messageId());
		verify(queue, never()).claim(anyLong(), any());
		verifyNoInteractions(processor, completion);
	}

	@Test
	@DisplayName("다른 실행이 가진 작업의 메시지(선점 실패)는 답변을 만들지 않고 ACK한다")
	void messageForJobHeldByAnotherRunIsAckedWithoutRunning() {
		ReceivedChatJob message = queueJobs(1).getFirst();
		when(queue.claim(anyLong(), any())).thenReturn(Optional.empty());
		worker = worker(1, Duration.ofSeconds(20));

		worker.processAvailableJobs();

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
		worker = worker(1, Duration.ofSeconds(20));
		worker.processAvailableJobs();
		verify(completion).complete(eq(1L), any(), eq(answered()), any());
		verify(stream).ack("message-1");
	}

	@Test
	void executionDoesNotRestartTimeoutAfterClaimDelay() {
		queueJobs(1);
		LocalDateTime expiredStart = LocalDateTime.now().minusMinutes(10);
		when(queue.claim(anyLong(), any())).thenReturn(claimed(expiredStart));
		when(processor.process(any(), any(), any(), any())).thenReturn(answered());
		worker = worker(1, Duration.ofSeconds(20));
		worker.processAvailableJobs();
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

		assertThat(ChatJobWorker.awaitUntil(future, deadline)).isEqualTo("답변");
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

		assertThatThrownBy(() -> ChatJobWorker.awaitUntil(late, deadline)).isInstanceOf(TimeoutException.class);
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
		worker = worker(1, Duration.ofSeconds(20));
		long began = System.nanoTime();
		worker.processAvailableJobs();
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
		worker = worker(1, Duration.ofSeconds(20));

		worker.processAvailableJobs();

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
		worker = worker(1, Duration.ofSeconds(20));

		assertThat(worker.processAvailableJobs()).isEqualTo(1);

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
		worker = worker(1, Duration.ofSeconds(20));

		worker.processAvailableJobs();

		verify(completion).complete(1L, STARTED_AT, ChatJobOutcome.failed(ChatErrorCode.STORAGE_UNAVAILABLE), RegionSido.SEOUL);
		verify(stream).ack(message.messageId());
		verifyNoInteractions(results);
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
	@DisplayName("다른 실행이 넘겨받아 결과를 기록하지 못한 실행은 메시지를 ACK하지 않는다")
	void unrecordedCompletionIsNotAcked() {
		queueJobs(1);
		when(processor.process(any(), any(), any(), any())).thenReturn(answered());
		when(completion.complete(anyLong(), any(), any(), any())).thenReturn(new Completion(false, null));
		worker = worker(1, Duration.ofSeconds(20));

		worker.processAvailableJobs();

		verify(stream, never()).ack(anyString());
		verifyNoInteractions(results);
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
		worker = worker(1, Duration.ofSeconds(20));

		worker.recover();

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
		worker = worker(1, Duration.ofSeconds(20));

		worker.recover();

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
		worker = worker(1, Duration.ofSeconds(20));

		worker.recover();

		verify(stream, never()).claim(eq("test-worker"), eq(HEARTBEAT_TIMEOUT), argThat(ids -> !ids.isEmpty()));
		verify(stream, never()).ack(anyString());
		verifyNoInteractions(processor);
	}

	@Test
	@DisplayName("내용을 읽지 못한 메시지는 heartbeat 단계에서 넘겨받지 않고 reclaimIdle 단계에 맡긴다")
	void unreadableIdleMessageIsLeftForReclaimIdle() {
		silentJobs(1);
		when(stream.findJobIds(anyList())).thenReturn(Map.of());
		worker = worker(1, Duration.ofSeconds(20));

		worker.recover();

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
		worker = worker(1, Duration.ofSeconds(20));

		worker.recover();

		verify(queue).takeOver(eq(1L), any(), any());
		verify(stream, never()).ack(anyString());
		verifyNoInteractions(processor);
	}

	@Test
	@DisplayName("넘겨받은 메시지의 작업이 이미 끝났으면(커밋 뒤 ACK 전에 죽음) 다시 실행하지 않고 ACK한다")
	void reclaimedMessageForFinishedJobIsAcked() {
		ReceivedChatJob message = staleJobs(1, 1).getFirst();
		ReflectionTestUtils.setField(storedJobs.get(1L), "status", ChatJobStatus.SUCCEEDED);
		worker = worker(1, Duration.ofSeconds(20));

		worker.recover();

		verify(stream).ack(message.messageId());
		verify(queue, never()).claim(anyLong(), any());
		verifyNoInteractions(processor);
	}

	@Test
	@DisplayName("전달 횟수를 다 쓴 메시지는 다시 실행하지 않고, 멈춘 작업을 CHAT-015로 끝낸 뒤 ACK한다")
	void exhaustedMessageFailsStuckJob() {
		ReceivedChatJob message = staleJobs(1, 3).getFirst();
		when(completion.failStuckRunning(eq(1L), any(), eq(ChatErrorCode.INTERNAL_ERROR))).thenReturn(true);
		worker = worker(1, Duration.ofSeconds(20));

		worker.recover();

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
		worker = worker(2, Duration.ofSeconds(20));

		worker.recover();

		verify(stream).claim("test-worker", RECLAIM_IDLE, List.of("message-1", "message-2"));
		verify(stream, timeout(2_000).times(2)).ack(anyString());
		verify(stream, never()).ack("message-3");
	}

	@Test
	@DisplayName("정리 작업은 시작된 지 넘겨받기 기준의 두 배가 지나도록 끝나지 않은 작업을 CHAT-015로 끝낸다")
	void recoveryFailsOrphanedRunningJobs() {
		when(queue.findRunningStartedBefore(any(), anyInt())).thenReturn(List.of(31L));
		worker = worker(1, Duration.ofSeconds(20));
		LocalDateTime before = LocalDateTime.now();

		worker.recover();

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
		worker = worker(1, Duration.ofSeconds(20));

		worker.recover();

		verify(completion).failStuckRunning(eq(31L), any(), eq(ChatErrorCode.INTERNAL_ERROR));
		verify(stream).trimAcknowledged();
	}

	@Test
	@DisplayName("정리 작업은 대기 한도를 넘긴 작업을 CHAT-016으로 끝낸다")
	void recoveryFailsJobsPastQueueWait() {
		when(queue.findExpiredQueued(anyInt(), eq(0L))).thenReturn(List.of(12L, 13L));
		worker = worker(1, Duration.ofSeconds(20));

		worker.recover();

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
		worker = worker(1, Duration.ofSeconds(20));

		worker.recover();

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
		worker = worker(1, Duration.ofSeconds(20));

		worker.recover();

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
		worker = worker(1, Duration.ofSeconds(20));

		worker.recover();

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
		worker = worker(1, Duration.ofSeconds(20));

		worker.recover();

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
		worker = worker(1, Duration.ofSeconds(20));

		worker.recover();
		worker.recover();
		verify(stream, org.mockito.Mockito.times(2)).publishIfAbsent(new ChatJobMessage(22L, CONTEXT));
	}

	private ChatJobWorker worker(int concurrency, Duration shutdownWait) {
		var properties = new ChatWorkerProperties(concurrency, true, Duration.ofMillis(50), "test-worker",
				Duration.ofMinutes(10), Duration.ofSeconds(30), RECLAIM_IDLE, 3, Duration.ofMinutes(1), Duration.ofHours(24),
				Duration.ofSeconds(180), HEARTBEAT_TIMEOUT, Duration.ofSeconds(20), Duration.ofSeconds(5));
		return new ChatJobWorker(stream, results, queue, jobs, completion, processor, attempts, properties, shutdownWait);
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
		worker = worker(1, Duration.ofSeconds(1));
		worker.start();
		assertThat(read.await(2, TimeUnit.SECONDS)).isTrue();
		// 읽기의 finally가 슬롯을 반환한 뒤에도 폴러는 계속 동작함
		waitUntil(() -> ((java.util.concurrent.Semaphore) ReflectionTestUtils.getField(worker, "slots")).availablePermits() == 1);
		worker.recover();
		verify(stream, timeout(2_000)).ack(stale.messageId());
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
		worker = new ChatJobWorker(stream, results, queue, jobs, completion, processor, attempts, properties);
		var registry = new io.micrometer.core.instrument.simple.SimpleMeterRegistry();
		worker.observeWith(registry);
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
		worker = new ChatJobWorker(stream, results, queue, jobs, completion, processor, attempts, properties);
		try {
			worker.start();
			verify(queue, timeout(2_000).atLeastOnce()).heartbeat(eq(1L), eq(STARTED_AT), any());
		} finally { release.countDown(); }
		verify(stream, timeout(2_000)).ack("message-1");
	}

	@Test
	@DisplayName("선점까지 기다린 시간과 성공 결과를 코드 없이(none) 센다")
	void recordsQueueWaitAndSuccessfulCompletion() {
		queueJobs(1);
		ReflectionTestUtils.setField(storedJobs.get(1L), "createdAt", STARTED_AT.minusSeconds(3));
		when(processor.process(any(), any(), any(), any())).thenReturn(answered());
		worker = worker(1, Duration.ofSeconds(1));
		var registry = observe(worker);

		worker.processAvailableJobs();

		var queueWait = registry.get("chat.jobs.queue.wait").timer();
		assertThat(queueWait.count()).isEqualTo(1);
		assertThat(queueWait.totalTime(TimeUnit.SECONDS)).isEqualTo(3.0);
		assertThat(registry.get("chat.worker.completed").tag("status", "succeeded").tag("code", "none")
				.counter().count()).isEqualTo(1);
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
		worker = worker(1, Duration.ofSeconds(20));
		var registry = observe(worker);

		worker.recover();

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
		worker = worker(1, Duration.ofSeconds(20));
		var registry = observe(worker);

		worker.recover();

		assertThat(registry.get("chat.worker.reclaimed").tag("reason", "idle").counter().count()).isEqualTo(1);
	}

	@Test
	@DisplayName("전달 횟수를 다 써 실패로 끝낸 작업은 terminated(reason=exhausted)로 센다")
	void exhaustedJobIsCountedAsTerminated() {
		staleJobs(1, 3);
		when(completion.failStuckRunning(eq(1L), any(), eq(ChatErrorCode.INTERNAL_ERROR))).thenReturn(true);
		worker = worker(1, Duration.ofSeconds(20));
		var registry = observe(worker);

		worker.recover();

		assertThat(registry.get("chat.worker.terminated").tag("reason", "exhausted").counter().count()).isEqualTo(1);
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
		worker = new ChatJobWorker(stream, results, queue, jobs, completion, processor, attempts, properties);
		var registry = observe(worker);
		try {
			worker.start();
			waitUntil(() -> registry.find("chat.worker.lease.lost").counter() != null);
			assertThat(registry.get("chat.worker.lease.lost").counter().count()).isGreaterThanOrEqualTo(1);
		} finally { release.countDown(); }
	}

	private static io.micrometer.core.instrument.simple.SimpleMeterRegistry observe(ChatJobWorker worker) {
		var registry = new io.micrometer.core.instrument.simple.SimpleMeterRegistry();
		worker.observeWith(registry);
		return registry;
	}

	@Test
	void republishScansBeyondFirstThousandPublishedJobs() {
		List<Long> firstPage = java.util.stream.LongStream.rangeClosed(1, 1000).boxed().toList();
		when(queue.findQueuedOlderThan(Duration.ofSeconds(30), 1000)).thenReturn(firstPage);
		when(queue.findQueuedOlderThanAfter(Duration.ofSeconds(30), 1000, 1000)).thenReturn(List.of(1001L));
		when(stream.findUnpublished(firstPage)).thenReturn(List.of());
		when(stream.findUnpublished(List.of(1001L))).thenReturn(List.of(1001L));
		when(jobs.findById(1001L)).thenReturn(Optional.of(ChatJob.queue(1001L, CONTEXT)));
		worker = worker(1, Duration.ofSeconds(1));
		worker.recover();
		verify(stream).publishIfAbsent(new ChatJobMessage(1001L, CONTEXT));
	}

	@Test
	void cacheWriteFailureStillAcknowledgesCommittedResult() {
		queueJobs(1);
		when(processor.process(any(), any(), any(), any())).thenReturn(answered());
		when(completion.complete(anyLong(), any(), any(), any())).thenReturn(new Completion(true, 10L));
		org.mockito.Mockito.doThrow(new DataAccessResourceFailureException("redis down"))
				.when(results).save(anyLong(), any(), any());
		worker = worker(1, Duration.ofSeconds(1));
		worker.processAvailableJobs();
		verify(stream).ack("message-1");
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
			worker.cancelJob(1L);
			assertThat(cancelledA.await(2, TimeUnit.SECONDS)).isTrue();
			assertThat(worker.isRunning()).isTrue();
			releaseB.countDown();
			verify(completion, timeout(2_000)).complete(eq(2L), any(), any(), any());
			verify(completion, never()).complete(eq(1L), any(), any(), any());
			verify(stream, timeout(2_000)).ack("message-1");
		} finally { releaseB.countDown(); }
	}

	@Test
	void cancellationCommittedAfterClaimDoesNotStartTheModel() {
		queueJobs(1);
		when(queue.claim(eq(1L), any())).thenAnswer(call -> {
			storedJobs.get(1L).fail();
			return claimed(STARTED_AT);
		});
		worker = worker(1, Duration.ofSeconds(20));
		worker.processAvailableJobs();
		verifyNoInteractions(processor, completion);
		verify(stream).ack("message-1");
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
			worker.cancelJob(1L);
			resume.countDown();
			verify(stream, timeout(2_000)).ack("message-1");
			verifyNoInteractions(processor, completion);
			assertThat(worker.isRunning()).isTrue();
		} finally {
			resume.countDown();
		}
	}

	// QUEUED 작업 count개의 메시지를 스트림에 넣음. 선점은 STARTED_AT으로 성공함
	private List<ReceivedChatJob> queueJobs(int count) {
		unread.addAll(storeJobs(count));
		List<ReceivedChatJob> queued = List.copyOf(unread);
		when(stream.read(anyString(), anyInt(), any())).thenAnswer(call -> {
			synchronized (unread) {
				return unread.isEmpty() ? List.of() : List.of(unread.poll());
			}
		});
		return queued;
	}

	// 다른 consumer가 받아 놓고 reclaimIdle 넘게 ACK 안 한 메시지 count개(deliveryCount번 전달됨)
	private List<ReceivedChatJob> staleJobs(int count, long deliveryCount) {
		List<ReceivedChatJob> stale = storeJobs(count);
		when(stream.findStale(eq(RECLAIM_IDLE), anyInt())).thenReturn(stale.stream()
				.map(received -> new PendingChatJob(received.messageId(), "dead-worker", deliveryCount))
				.toList());
		when(stream.claim(eq("test-worker"), eq(RECLAIM_IDLE), anyList())).thenAnswer(call -> stale.stream()
				.filter(received -> call.<List<String>>getArgument(2).contains(received.messageId()))
				.toList());
		return stale;
	}

	// 다른 consumer가 받아 놓고 heartbeat 기준(1분) 넘게 ACK 안 한 메시지. 작업 ID는 읽을 수 있음
	private List<ReceivedChatJob> silentJobs(int count) {
		List<ReceivedChatJob> idle = storeJobs(count);
		when(stream.findStale(eq(HEARTBEAT_TIMEOUT), anyInt())).thenReturn(idle.stream()
				.map(received -> new PendingChatJob(received.messageId(), "dead-worker", 1))
				.toList());
		when(stream.findJobIds(anyList())).thenAnswer(call -> idle.stream()
				.filter(received -> call.<List<String>>getArgument(0).contains(received.messageId()))
				.collect(java.util.stream.Collectors.toMap(ReceivedChatJob::messageId, received -> received.message().jobId())));
		when(stream.claim(eq("test-worker"), eq(HEARTBEAT_TIMEOUT), anyList())).thenAnswer(call -> idle.stream()
				.filter(received -> call.<List<String>>getArgument(2).contains(received.messageId()))
				.toList());
		return idle;
	}

	// QUEUED 작업 count개와 각 메시지. 선점은 STARTED_AT으로 성공함
	private List<ReceivedChatJob> storeJobs(int count) {
		STARTED_AT = LocalDateTime.now();
		List<ReceivedChatJob> messages = new ArrayList<>();
		for (long id = 1; id <= count; id++) {
			ChatJob job = ChatJob.queue(id, CONTEXT);
			ReflectionTestUtils.setField(job, "id", id);
			storedJobs.put(id, job);
			messages.add(new ReceivedChatJob("message-" + id, new ChatJobMessage(id, CONTEXT)));
		}
		when(jobs.findById(anyLong())).thenAnswer(call -> Optional.ofNullable(storedJobs.get(call.<Long>getArgument(0))));
		// 다른 테스트가 stub을 다시 걸 때 matcher 값(null)으로도 호출되므로 null이면 빈 값을 돌려줌
		when(queue.claim(anyLong(), any())).thenAnswer(call -> Optional.ofNullable(
						call.<Long>getArgument(0) == 1L ? STARTED_AT : call.<LocalDateTime>getArgument(1))
				.map(startedAt -> new Claim(startedAt, startedAt.plus(claimedExecution))));
		when(completion.complete(anyLong(), any(), any(), any())).thenReturn(new Completion(true, null));
		when(attempts.findById(anyLong())).thenAnswer(call -> Optional.of(AnswerAttemptsHistory.builder()
				.id(call.getArgument(0)).userId(1L).question("질문").attemptCount(1).status("PENDING").build()));
		return messages;
	}

	private static Optional<Claim> claimed(LocalDateTime startedAt) {
		return claimed(startedAt, Duration.ofSeconds(180));
	}

	private static Optional<Claim> claimed(LocalDateTime startedAt, Duration executionTimeout) {
		return Optional.of(new Claim(startedAt, startedAt.plus(executionTimeout)));
	}

	private static ChatJobOutcome answered() {
		return new ChatJobOutcome("답변", List.of(), null, null, List.of());
	}

	private static void waitUntil(java.util.function.BooleanSupplier condition) throws InterruptedException {
		long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
		while (!condition.getAsBoolean()) {
			if (System.nanoTime() > deadline) {
				throw new AssertionError("제한 시간 안에 조건을 만족하지 못했습니다.");
			}
			Thread.sleep(10);
		}
	}
}
