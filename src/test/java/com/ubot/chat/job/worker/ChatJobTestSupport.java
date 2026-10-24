package com.ubot.chat.job.worker;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.ubot.chat.entity.AnswerAttemptsHistory;
import com.ubot.chat.job.dto.ChatRequestContext;
import com.ubot.chat.job.entity.ChatJob;
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
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import com.ubot.chat.job.support.ChatJobTestDriver;
import org.springframework.test.util.ReflectionTestUtils;

abstract class ChatJobTestSupport {
	LocalDateTime STARTED_AT = LocalDateTime.now();
	static final ChatRequestContext CONTEXT = new ChatRequestContext(null, null, RegionSido.SEOUL);
	static final Duration RECLAIM_IDLE = Duration.ofMinutes(5);
	// RECLAIM_IDLE만 주는 생성자의 기본 heartbeat 기준
	static final Duration HEARTBEAT_TIMEOUT = Duration.ofMinutes(1);

	final ChatJobStream stream = mock(ChatJobStream.class);
	final ChatJobResultStore results = mock(ChatJobResultStore.class);
	final ChatJobQueueRepository queue = mock(ChatJobQueueRepository.class);
	final ChatJobRepository jobs = mock(ChatJobRepository.class);
	final ChatJobCompletionService completion = mock(ChatJobCompletionService.class);
	final ChatJobProcessor processor = mock(ChatJobProcessor.class);
	final AnswerAttemptsHistoryRepository attempts = mock(AnswerAttemptsHistoryRepository.class);
	final Deque<ReceivedChatJob> unread = new ArrayDeque<>();
	final Map<Long, ChatJob> storedJobs = new HashMap<>();
	// 선점 SQL이 deadline_at을 잡을 때 쓰는 실행 한도. 기본 선점 mock이 이 값으로 마감을 돌려줌
	Duration claimedExecution = Duration.ofSeconds(180);
	ChatJobRunner runner;
	ChatJobRecoveryService recovery;
	ChatJobTestDriver driver;

	@AfterEach
	void closeRunner() {
		if (runner != null) runner.close();
	}

	ChatWorkerProperties properties(int concurrency, Duration shutdownWait) {
		return new ChatWorkerProperties(concurrency, true, Duration.ofMillis(50), "test-worker",
				Duration.ofMinutes(10), Duration.ofSeconds(30), RECLAIM_IDLE, 3, Duration.ofMinutes(1), Duration.ofHours(24),
				Duration.ofSeconds(180), HEARTBEAT_TIMEOUT, shutdownWait, Duration.ofSeconds(5));
	}

	void configure(int concurrency, Duration shutdownWait) { configure(properties(concurrency, shutdownWait)); }

	void configure(ChatWorkerProperties properties) {
		runner = new ChatJobRunner(stream, results, queue, jobs, completion, processor, attempts, properties);
		recovery = new ChatJobRecoveryService(stream, queue, jobs, completion, properties, runner);
		driver = new ChatJobTestDriver(stream, runner, recovery, properties);
	}

	io.micrometer.core.instrument.simple.SimpleMeterRegistry observe() {
		var registry = new io.micrometer.core.instrument.simple.SimpleMeterRegistry();
		runner.observeWith(registry);
		recovery.observeWith(registry);
		return registry;
	}

	List<ReceivedChatJob> queueJobs(int count) {
		unread.addAll(storeJobs(count));
		List<ReceivedChatJob> queued = List.copyOf(unread);
		when(stream.read(anyString(), anyInt(), any())).thenAnswer(call -> {
			synchronized (unread) {
				return unread.isEmpty() ? List.of() : List.of(unread.poll());
			}
		});
		return queued;
	}

	List<ReceivedChatJob> staleJobs(int count, long deliveryCount) {
		List<ReceivedChatJob> stale = storeJobs(count);
		when(stream.findStale(eq(RECLAIM_IDLE), anyInt())).thenReturn(stale.stream()
				.map(received -> new PendingChatJob(received.messageId(), "dead-worker", deliveryCount))
				.toList());
		when(stream.claim(eq("test-worker"), eq(RECLAIM_IDLE), anyList())).thenAnswer(call -> stale.stream()
				.filter(received -> call.<List<String>>getArgument(2).contains(received.messageId()))
				.toList());
		return stale;
	}

	List<ReceivedChatJob> silentJobs(int count) {
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

	List<ReceivedChatJob> storeJobs(int count) {
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

	static ChatJobOutcome answered() {
		return new ChatJobOutcome("답변", List.of(), null, null, List.of());
	}

	static void waitUntil(java.util.function.BooleanSupplier condition) throws InterruptedException {
		long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
		while (!condition.getAsBoolean()) {
			if (System.nanoTime() > deadline) {
				throw new AssertionError("제한 시간 안에 조건을 만족하지 못했습니다.");
			}
			Thread.sleep(10);
		}
	}

	static Optional<Claim> claimed(LocalDateTime startedAt) {
		return claimed(startedAt, Duration.ofSeconds(180));
	}

	static Optional<Claim> claimed(LocalDateTime startedAt, Duration executionTimeout) {
		return Optional.of(new Claim(startedAt, startedAt.plus(executionTimeout)));
	}

}
