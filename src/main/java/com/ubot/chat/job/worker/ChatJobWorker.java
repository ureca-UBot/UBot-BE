package com.ubot.chat.job.worker;

import com.ubot.ai.progress.AnswerProgress;
import com.ubot.ai.progress.AnswerProgress.Phase;
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
import com.ubot.common.exception.SafeExceptions;
import com.ubot.llm.client.LlmRecovery;
import com.ubot.unanswered.dto.UnansweredCandidate;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.FutureTask;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.context.SmartLifecycle;
import org.springframework.dao.DataIntegrityViolationException;

// 스트림 메시지를 읽어 실행함. 동시 실행은 concurrency개 이하이고 빈 슬롯이 있을 때만 읽음
// QUEUED→RUNNING으로 바꾼 한 실행만 답변을 만들고 DB 커밋 뒤에만 ACK함. 모델 호출 중 트랜잭션은 잡지 않음
@Slf4j
public class ChatJobWorker implements SmartLifecycle {
	private static final int RECOVERY_BATCH = 100;
	// 정리 한 번이 길어지지 않게 발행 표시 확인 수를 제한함
	private static final int REPUBLISH_SCAN_LIMIT = 1000;
	// 정리 한 번이 길어져 다른 단계·health 확인이 밀리지 않게 상한을 둠
	private static final int EXPIRE_SCAN_LIMIT = 1000;
	// Redis 초기화 시 대기 작업 전체를 대기 한도 안에 재발행하도록 넉넉히 둠
	private static final int REPUBLISH_LIMIT = 1000;

	private final ChatJobStream stream;
	private final ChatJobResultStore resultStore;
	private final ChatJobQueueRepository queueRepository;
	private final ChatJobRepository jobRepository;
	private final ChatJobCompletionService completionService;
	private final ChatJobProcessor jobProcessor;
	private final AnswerAttemptsHistoryRepository answerAttemptsHistoryRepository;
	private final ChatWorkerProperties properties;
	private final Duration shutdownWait;
	private final Semaphore slots;
	private final Set<RunningJob> runningJobs = ConcurrentHashMap.newKeySet();
	private final ExecutorService jobExecutor = Executors.newThreadPerTaskExecutor(
			Thread.ofVirtual().name("chat-job-", 0).factory());
	private final ExecutorService computations = Executors.newVirtualThreadPerTaskExecutor();
	private final ScheduledExecutorService leases = Executors.newSingleThreadScheduledExecutor(
			Thread.ofPlatform().name("chat-job-lease").daemon().factory());
	private final ScheduledExecutorService timers = Executors.newScheduledThreadPool(1,
			Thread.ofPlatform().name("chat-job-timer").daemon().factory());

	private volatile boolean running;
	private Thread poller;
	private MeterRegistry metrics;
	private volatile long lastRecoveryNanos;
	// 소비 스레드에서만 접근함. 같은 읽기 장애의 반복 경고를 줄이고 복구 시점을 기록함
	private String streamReadFailure;

	void observeWith(MeterRegistry registry) {
		metrics = registry;
		registry.gauge("chat.worker.active", runningJobs, Set::size);
		registry.gauge("chat.worker.ready", this, worker -> worker.isOperational() ? 1 : 0);
		registry.gauge("chat.jobs.queued", queueRepository, ChatJobQueueRepository::queuedCount);
		registry.gauge("chat.jobs.oldest.queued.seconds", queueRepository, ChatJobQueueRepository::oldestQueuedSeconds);
	}

	public boolean isOperational() {
		return running && properties.pollingEnabled() && poller != null && poller.isAlive()
				&& System.nanoTime() - lastRecoveryNanos < properties.recoveryInterval().multipliedBy(3).toNanos();
	}

	private void count(String name, String... tags) {
		if (metrics != null) metrics.counter("chat.worker." + name, tags).increment();
	}

	public ChatJobWorker(
			ChatJobStream stream,
			ChatJobResultStore resultStore,
			ChatJobQueueRepository queueRepository,
			ChatJobRepository jobRepository,
			ChatJobCompletionService completionService,
			ChatJobProcessor jobProcessor,
			AnswerAttemptsHistoryRepository answerAttemptsHistoryRepository,
			ChatWorkerProperties properties) {
		this(stream, resultStore, queueRepository, jobRepository, completionService, jobProcessor,
				answerAttemptsHistoryRepository, properties, properties.shutdownWait());
	}

	ChatJobWorker(
			ChatJobStream stream,
			ChatJobResultStore resultStore,
			ChatJobQueueRepository queueRepository,
			ChatJobRepository jobRepository,
			ChatJobCompletionService completionService,
			ChatJobProcessor jobProcessor,
			AnswerAttemptsHistoryRepository answerAttemptsHistoryRepository,
			ChatWorkerProperties properties,
			Duration shutdownWait) {
		this.stream = stream;
		this.resultStore = resultStore;
		this.queueRepository = queueRepository;
		this.jobRepository = jobRepository;
		this.completionService = completionService;
		this.jobProcessor = jobProcessor;
		this.answerAttemptsHistoryRepository = answerAttemptsHistoryRepository;
		this.properties = properties;
		this.shutdownWait = shutdownWait;
		this.slots = new Semaphore(properties.concurrency());
	}

	@Override
	public void start() {
		running = true;
		lastRecoveryNanos = System.nanoTime();
		if (!properties.pollingEnabled()) {
			log.info("답변 Worker의 스트림 읽기가 꺼져 있습니다(app.chat.worker.polling-enabled=false).");
			return;
		}
		ensureGroupQuietly();
		long heartbeatMillis = Math.max(1, Math.min(15_000, properties.heartbeatTimeout().toMillis() / 3));
		leases.scheduleWithFixedDelay(this::renewLeases, heartbeatMillis, heartbeatMillis, TimeUnit.MILLISECONDS);
		long recoveryMillis = properties.recoveryInterval().toMillis();
		timers.scheduleWithFixedDelay(this::recover, recoveryMillis, recoveryMillis, TimeUnit.MILLISECONDS);
		poller = Thread.ofPlatform().name("chat-job-poller").start(this::pollLoop);
		log.info("답변 Worker를 시작했습니다: 동시실행수={}, consumer={}, 대기한도={}, 넘겨받기기준={}, 최대전달횟수={}, 실행한도={}, 모델재시도간격={}",
				properties.concurrency(), properties.consumerName(), properties.maxQueueWait(),
				properties.reclaimIdle(), properties.maxDeliveries(), properties.executionTimeout(), properties.modelRetryBackoff());
	}

	// 실행 중 작업은 shutdownWait까지 기다리고, 못 끝낸 작업은 대기열에 돌려놓고 재발행함
	@Override
	public void stop() {
		log.info("답변 Worker를 종료합니다: consumer={}, 실행중작업수={}, 종료대기={}",
				properties.consumerName(), runningJobs.size(), shutdownWait);
		running = false;
		if (poller != null) {
			poller.interrupt();
		}
		timers.shutdownNow();
		jobExecutor.shutdown();
		try {
			if (!jobExecutor.awaitTermination(shutdownWait.toMillis(), TimeUnit.MILLISECONDS)) {
				log.warn("종료 대기 시간 안에 끝나지 않은 답변 작업을 대기열에 돌려놓습니다: 작업수={}", runningJobs.size());
				releaseUnfinished();
			}
		} catch (InterruptedException exception) {
			Thread.currentThread().interrupt();
			releaseUnfinished();
		}
		leases.shutdownNow();
		computations.shutdownNow();
		log.info("답변 Worker 종료 처리를 마쳤습니다: consumer={}", properties.consumerName());
	}

	@Override
	public boolean isRunning() {
		return running;
	}

	// 스트림 읽기를 끈 테스트용. 받을 수 있는 메시지를 호출 스레드에서 모두 처리함
	public int processAvailableJobs() {
		stream.ensureGroup();
		int processed = 0;
		List<ReceivedChatJob> received;
		while (!(received = stream.read(properties.consumerName(), 1, null)).isEmpty()) {
			handle(received.getFirst());
			processed++;
		}
		return processed;
	}

	// 정리 작업. 단계 하나가 실패해도(Redis 장애 등) 다음 단계는 진행함
	public void recover() {
		runStep("대기 한도를 넘긴 작업 정리", this::expireQueued);
		runStep("발행되지 않은 작업 다시 발행", this::republishUnpublished);
		runStep("heartbeat가 끊긴 실행 넘겨받기", this::reclaimSilentRuns);
		runStep("방치된 메시지 넘겨받기", this::reclaimStale);
		runStep("메시지를 잃은 작업 정리", this::failOrphaned);
		runStep("스트림 정리", this::trimAcknowledged);
		lastRecoveryNanos = System.nanoTime();
	}

	// RECOVERY_BATCH개씩, 남아 있으면 EXPIRE_SCAN_LIMIT개까지 이어서 끝냄. 나머지는 다음 정리가 처리함
	// 선점 SQL도 만료 작업을 거절하므로 늦게 끝내도 실행되지 않음
	private void expireQueued() {
		int expired = 0;
		int scanned = 0;
		List<Long> batch = queueRepository.findExpiredQueued(RECOVERY_BATCH, 0);
		while (!batch.isEmpty() && !Thread.currentThread().isInterrupted()) {
			for (Long jobId : batch) {
				if (completionService.failQueued(jobId, ChatErrorCode.RESPONSE_TIMEOUT)) {
					expired++;
					count("queue.expired");
				}
			}
			scanned += batch.size();
			if (batch.size() < RECOVERY_BATCH || scanned >= EXPIRE_SCAN_LIMIT) {
				break;
			}
			batch = queueRepository.findExpiredQueued(RECOVERY_BATCH, batch.getLast());
		}
		if (expired > 0) {
			log.warn("대기 한도를 넘긴 답변 작업을 실패로 끝냈습니다: 작업수={}", expired);
		}
	}

	// DB 요청 정보로 재발행함. 페이지를 넘겨 뒤쪽 누락도 찾음
	private void republishUnpublished() {
		List<Long> waiting = queueRepository.findQueuedOlderThan(properties.republishAfter(), REPUBLISH_SCAN_LIMIT);
		int republished = 0;
		while (!waiting.isEmpty() && !Thread.currentThread().isInterrupted()) {
			for (Long jobId : stream.findUnpublished(waiting)) {
				if (republished >= REPUBLISH_LIMIT) return;
				try {
					Optional<ChatJob> job = jobRepository.findById(jobId);
					if (job.isPresent() && stream.publishIfAbsent(new ChatJobMessage(jobId,
							Optional.ofNullable(job.get().getRequestContext()).orElse(ChatRequestContext.EMPTY)))) {
						republished++;
					}
				} catch (RuntimeException exception) {
					log.warn("답변 작업 메시지를 다시 발행하지 못했습니다. 다음 정리에서 다시 시도합니다: 작업ID={}, 예외종류={}", jobId, SafeExceptions.type(exception));
				}
			}
			if (waiting.size() < REPUBLISH_SCAN_LIMIT) break;
			waiting = queueRepository.findQueuedOlderThanAfter(properties.republishAfter(), REPUBLISH_SCAN_LIMIT,
					waiting.getLast());
		}
		if (republished > 0) {
			log.warn("발행되지 않은 답변 작업을 다시 발행했습니다: 작업수={}", republished);
		}
	}

	// Worker가 죽었을 때 reclaimIdle까지 기다리지 않게 하는 단계
	// heartbeat가 끊긴 실행·대기·종료 작업의 메시지만 넘겨받음. 살아 있는 실행은 건드리지 않아 전달 횟수가 늘지 않음
	private void reclaimSilentRuns() {
		List<PendingChatJob> idle = stream.findStale(properties.heartbeatTimeout(), RECOVERY_BATCH);
		if (idle.isEmpty()) {
			return;
		}
		Map<String, Long> jobIds = stream.findJobIds(idle.stream().map(PendingChatJob::messageId).toList());
		LocalDateTime silentBefore = stuckBefore(now());
		List<PendingChatJob> silent = idle.stream()
				.filter(pending -> jobIds.containsKey(pending.messageId())
						&& isNotRunningLive(jobIds.get(pending.messageId()), silentBefore))
				.toList();
		int started = reclaim(silent, properties.heartbeatTimeout(), "heartbeat");
		if (started > 0) {
			log.warn("heartbeat가 끊긴 답변 작업의 메시지를 넘겨받아 다시 실행합니다: 작업수={}", started);
		}
	}

	// 실행 중이고 heartbeat가 살아 있으면 false. 없는 작업·대기·종료 작업은 take가 처리함
	private boolean isNotRunningLive(long jobId, LocalDateTime silentBefore) {
		return jobRepository.findById(jobId)
				.map(job -> job.getStatus() != ChatJobStatus.RUNNING || lastSign(job).isBefore(silentBefore))
				.orElse(true);
	}

	private static LocalDateTime lastSign(ChatJob job) {
		LocalDateTime sign = job.getHeartbeatAt() != null ? job.getHeartbeatAt() : job.getStartedAt();
		return sign == null ? LocalDateTime.MIN : sign;
	}

	// 상태를 보지 않고 넘겨받는 마지막 안전망. 실행 중 작업은 take에서 heartbeat를 다시 확인해 빼앗지 않음
	private void reclaimStale() {
		int started = reclaim(stream.findStale(properties.reclaimIdle(), RECOVERY_BATCH), properties.reclaimIdle(), "idle");
		if (started > 0) {
			log.warn("오래 처리되지 않은 답변 작업 메시지를 넘겨받아 다시 실행합니다: 작업수={}", started);
		}
	}

	// 빈 슬롯 수만큼만 넘겨받고 시작한 수를 반환함. XCLAIM이 minIdle을 다시 확인해 한 Worker만 받음
	// reason: 지표에 남길 넘겨받은 이유(heartbeat 끊김·ACK 지연)
	private int reclaim(List<PendingChatJob> stale, Duration minIdle, String reason) {
		failExhausted(stale.stream()
				.filter(pending -> pending.deliveryCount() >= properties.maxDeliveries())
				.map(PendingChatJob::messageId)
				.toList(), minIdle);
		List<String> retryable = stale.stream()
				.filter(pending -> pending.deliveryCount() < properties.maxDeliveries())
				.map(PendingChatJob::messageId)
				.toList();
		int permits = acquireFreeSlots(retryable.size());
		int started = 0;
		try {
			for (ReceivedChatJob received : stream.claim(properties.consumerName(), minIdle,
					retryable.subList(0, permits))) {
				Optional<ClaimedJob> claimed = takeQuietly(received, true);
				if (claimed.isPresent()) {
					started++;
					count("reclaimed", "reason", reason);
					startInSlot(received, claimed.get());
				}
			}
		} finally {
			slots.release(permits - started);
		}
		return started;
	}

	// 실행할 때마다 Worker를 멈추게 하는 작업일 수 있어 재실행 없이 실패 처리함
	// 그새 끝났거나 다른 실행이 넘겨받았으면 작업은 두고 메시지만 ACK함
	private void failExhausted(List<String> messageIds, Duration minIdle) {
		int failed = 0;
		for (ReceivedChatJob received : stream.claim(properties.consumerName(), minIdle, messageIds)) {
			long jobId = received.message().jobId();
			try {
				boolean closed = completionService.failStuckRunning(jobId, stuckBefore(now()), ChatErrorCode.INTERNAL_ERROR)
						|| completionService.failQueued(jobId, ChatErrorCode.INTERNAL_ERROR);
				if (closed) {
					failed++;
					count("terminated", "reason", "exhausted");
				}
				if (closed || jobRepository.findById(jobId).map(job -> job.getStatus().isFinished()).orElse(true)) {
					ackQuietly(received);
				}
			} catch (RuntimeException exception) {
				log.warn("전달 횟수를 다 쓴 답변 작업을 끝내지 못했습니다. 다음 정리에서 다시 시도합니다: 작업ID={}, 예외종류={}", jobId, SafeExceptions.type(exception));
			}
		}
		if (failed > 0) {
			log.error("전달 횟수({}번)를 다 쓴 답변 작업을 실패로 끝냈습니다: 작업수={}", properties.maxDeliveries(), failed);
		}
	}

	// 메시지 없이 heartbeat가 reclaimIdle의 두 배 동안 없는 작업을 끝냄
	private void failOrphaned() {
		LocalDateTime startedBefore = now().minus(properties.reclaimIdle().multipliedBy(2));
		int failed = 0;
		for (Long jobId : queueRepository.findRunningStartedBefore(startedBefore, RECOVERY_BATCH)) {
			if (completionService.failStuckRunning(jobId, startedBefore, ChatErrorCode.INTERNAL_ERROR)) {
				failed++;
				count("terminated", "reason", "orphaned");
			}
		}
		if (failed > 0) {
			log.error("메시지 없이 오래 실행 중으로 남은 답변 작업을 실패로 끝냈습니다: 작업수={}", failed);
		}
	}

	private void trimAcknowledged() {
		long trimmed = stream.trimAcknowledged();
		if (trimmed > 0) {
			log.debug("ACK된 답변 작업 메시지를 스트림에서 지웠습니다: 메시지수={}", trimmed);
		}
	}

	private void pollLoop() {
		while (running) {
			try {
				slots.acquire();
			} catch (InterruptedException exception) {
				return;
			}
			if (!running) {
				slots.release();
				return;
			}
			boolean started = false;
			try {
				// 슬롯을 잡은 채 기다리면 concurrency=1에서 복구가 굶음
				List<ReceivedChatJob> received = stream.read(properties.consumerName(), 1, null);
				if (streamReadFailure != null) {
					log.info("답변 작업 스트림 읽기가 복구되었습니다: consumer={}, 이전예외={}",
							properties.consumerName(), streamReadFailure);
					streamReadFailure = null;
				}
				if (!received.isEmpty()) {
					ReceivedChatJob next = received.getFirst();
					try {
						jobExecutor.execute(() -> {
							try {
								handle(next);
							} finally {
								slots.release();
							}
						});
						started = true;
					} catch (RejectedExecutionException exception) {
						// 종료 중에 받은 메시지는 재발행해 다른 Worker가 받게 함
						republishQuietly(next);
					}
				}
			} catch (RuntimeException exception) {
				handleReadFailure(exception);
			} finally {
				if (!started) {
					slots.release();
				}
			}
			if (!started && running) sleep(properties.readBlock());
		}
	}

	private void handleReadFailure(RuntimeException exception) {
		boolean missingGroup = ChatJobStream.isMissingGroup(exception);
		String failure = missingGroup ? "NOGROUP" : SafeExceptions.type(exception);
		if (!failure.equals(streamReadFailure)) {
			log.warn("답변 작업 스트림을 읽지 못했습니다: consumer={}, 예외종류={}", properties.consumerName(), failure);
		} else {
			log.debug("답변 작업 스트림 읽기를 재시도합니다: consumer={}, 예외종류={}", properties.consumerName(), failure);
		}
		streamReadFailure = failure;
		if (missingGroup) {
			log.debug("답변 작업 스트림 그룹이 없어 다시 만듭니다.");
			ensureGroupQuietly();
			return;
		}
		sleep(properties.readBlock());
	}

	private void handle(ReceivedChatJob received) {
		takeQuietly(received, false).ifPresent(job -> execute(received, job));
	}

	private Optional<ClaimedJob> takeQuietly(ReceivedChatJob received, boolean reclaimed) {
		try {
			return take(received, reclaimed);
		} catch (RuntimeException exception) {
			// DB 장애 등으로 확인 못 함. ACK하지 않아 나중에 넘겨받음
			log.error("답변 작업을 시작하지 못해 메시지를 남겨 둡니다: 작업ID={}, 메시지ID={}, 예외종류={}",
					received.message().jobId(), received.messageId(), SafeExceptions.type(exception),
					SafeExceptions.withoutMessages(exception));
			return Optional.empty();
		}
	}

	// 실행하지 않을 메시지(없는·끝난·다른 실행이 가진 작업)는 ACK하고 빈 값
	// reclaimed면 멈춘 실행도 넘겨받고, 살아 있는 실행이면 ACK 없이 남겨 둠
	private Optional<ClaimedJob> take(ReceivedChatJob received, boolean reclaimed) {
		long jobId = received.message().jobId();
		Optional<ChatJob> job = jobRepository.findById(jobId);
		if (job.isEmpty()) {
			log.warn("없는 작업의 메시지라 건너뜁니다: 작업ID={}, 메시지ID={}", jobId, received.messageId());
			ackQuietly(received);
			return Optional.empty();
		}
		if (job.get().getStatus().isFinished()) {
			log.info("이미 끝난 작업의 메시지라 다시 실행하지 않습니다: 작업ID={}, 메시지ID={}", jobId, received.messageId());
			ackQuietly(received);
			return Optional.empty();
		}
		LocalDateTime now = now();
		Optional<Claim> claim = queueRepository.claim(jobId, now);
		claim.ifPresent(claimed -> recordQueueWait(job.get(), claimed.startedAt()));
		if (claim.isEmpty() && reclaimed) {
			claim = queueRepository.takeOver(jobId, now, stuckBefore(now));
			if (claim.isEmpty()) {
				log.info("실행 중인 작업이라 넘겨받은 메시지를 남겨 둡니다: 작업ID={}, 메시지ID={}", jobId, received.messageId());
				return Optional.empty();
			}
			log.warn("멈춘 실행의 답변 작업을 넘겨받았습니다: 작업ID={}, 메시지ID={}", jobId, received.messageId());
		}
		if (claim.isEmpty()) {
			log.info("다른 실행이 가진 작업의 메시지라 건너뜁니다: 작업ID={}, 메시지ID={}", jobId, received.messageId());
			ackQuietly(received);
			return Optional.empty();
		}
		// 마감은 선점 SQL이 저장한 deadline_at을 그대로 씀. 상태 조회 응답의 마감과 같은 값임
		return Optional.of(new ClaimedJob(job.get().getAttemptId(), claim.get().startedAt(),
				Optional.ofNullable(job.get().getRequestContext()).orElse(received.message().context()),
				claim.get().deadline()));
	}

	// 접수부터 선점까지 걸린 시간. 실행 시간(chat.worker.execution)과 나눠 대기열 적체를 봄
	private void recordQueueWait(ChatJob job, LocalDateTime claimedAt) {
		if (job.getCreatedAt() == null) return;
		Duration waited = Duration.between(job.getCreatedAt(), claimedAt);
		if (!waited.isNegative()) {
			log.debug("답변 작업을 선점했습니다: 작업ID={}, 시도ID={}, 대기시간={}ms, consumer={}",
					job.getId(), job.getAttemptId(), waited.toMillis(), properties.consumerName());
			if (metrics != null) metrics.timer("chat.jobs.queue.wait").record(waited);
		}
	}

	// 잡아 둔 슬롯에서 실행하고 끝나면 반환함. 스트림 읽기를 끈 테스트에서는 호출 스레드에서 실행함
	private void startInSlot(ReceivedChatJob received, ClaimedJob claimed) {
		Runnable task = () -> {
			try {
				execute(received, claimed);
			} finally {
				slots.release();
			}
		};
		if (!properties.pollingEnabled()) {
			task.run();
			return;
		}
		try {
			jobExecutor.execute(task);
		} catch (RejectedExecutionException exception) {
			slots.release();
			returnToQueue(received, claimed.startedAt());
		}
	}


	/** 로컬 실행 중 해당 ID 하나만 중단합니다. 분리된 Worker에는 Redis 취소 알림으로 전달됩니다. */
	public void cancelJob(long jobId) {
		for (RunningJob job : runningJobs) {
			if (job.received.message().jobId() == jobId) job.cancel();
		}
	}

	private void execute(ReceivedChatJob received, ClaimedJob claimed) {
		long executionStarted = System.nanoTime();
		ChatJobMessage message = new ChatJobMessage(received.message().jobId(), claimed.context());
		RunningJob job = new RunningJob(received, claimed.startedAt());
		runningJobs.add(job);
		try {
			// 선점 직후 사용자 취소가 커밋되었으면 모델을 호출하지 않습니다.
			if (jobRepository.findById(message.jobId()).map(found -> found.getStatus().isFinished()).orElse(false)) {
				ackQuietly(received);
				return;
			}
			log.info("답변 작업을 시작합니다: 작업ID={}, 시도ID={}, 메시지ID={}, consumer={}, 마감시각={}",
					message.jobId(), claimed.attemptId(), received.messageId(), properties.consumerName(), claimed.deadline());
			AnswerAttemptsHistory attempt = answerAttemptsHistoryRepository.findById(claimed.attemptId()).orElseThrow();
			FutureTask<ChatJobOutcome> computation = new FutureTask<>(() -> {
				// 계산은 별도 가상 스레드에서 실행함. 로그 패턴(logging.pattern.correlation)이 찍을 작업ID·시도ID를 여기서 MDC에 연결함
				try (var jobId = MDC.putCloseable("jobId", Long.toString(message.jobId()));
						var attemptId = MDC.putCloseable("attemptId", Long.toString(claimed.attemptId()))) {
					job.checkActive();
					return AnswerProgress.observe(phase -> reportPhase(job, phase),
							() -> LlmRecovery.observe(properties.modelRetryBackoff(), job::checkActive,
									() -> {
										count("model.retry.waits");
										reportPhase(job, Phase.WAITING_FOR_MODEL);
									}, () -> jobProcessor.process(attempt, message.context().location(), job::checkActive, job.unanswered)));
				}
			});
			// 실행 전에 Future를 연결합니다. 연결 직전 취소도 기억해 모델을 시작하지 않습니다.
			job.bindComputation(computation);
			ChatJobOutcome outcome;
			try {
				// 선점 때 안내한 마감까지의 시간만 사용. 단계 전환으로 시간을 연장하지 않음.
				// 마감이 이미 지났으면 모델을 시작하지 않음
				if (!now().isBefore(claimed.deadline())) throw new TimeoutException("답변 처리 제한 시간이 지났습니다.");
				computations.execute(computation);
				outcome = awaitUntil(computation, claimed.deadline());
				job.checkActive();
			} catch (TimeoutException exception) {
				count("timeouts");
				log.warn("답변 작업 실행 한도를 넘겼습니다: 작업ID={}, 시도ID={}, 오류코드={}, 마감시각={}, 실행시간={}ms",
						message.jobId(), claimed.attemptId(), ChatErrorCode.RESPONSE_TIMEOUT.getCode(), claimed.deadline(),
						elapsedMillis(executionStarted));
				job.cancel();
				outcome = ChatJobOutcome.failed(ChatErrorCode.RESPONSE_TIMEOUT, job.unanswered);
			} catch (InterruptedException exception) {
				job.cancel();
				Thread.currentThread().interrupt();
				throw new CancellationException("Worker interrupted");
			} catch (ExecutionException exception) {
				if (exception.getCause() instanceof RuntimeException cause) throw cause;
				throw new IllegalStateException(exception.getCause());
			}
			Completion completion = complete(message, claimed.startedAt(), outcome);
			if (!completion.recorded()) {
				count("discarded");
				log.info("실행 권한을 잃어 답변 결과를 기록하지 않았습니다: 작업ID={}, 시도ID={}, 실행시간={}ms",
						message.jobId(), claimed.attemptId(), elapsedMillis(executionStarted));
				// 다른 실행이 넘겨받았거나 정리 작업이 끝냄. 넘겨받은 쪽이 ACK하므로 여기서는 ACK 안 함
				return;
			}
			// complete가 반환된 뒤에는 DB 커밋이 끝남. Redis 결과 캐시·ACK 성공 여부와 구분함
			log.info("답변 작업의 결과 저장을 마쳤습니다: 작업ID={}, 시도ID={}, 답변저장={}, 실행시간={}ms",
					message.jobId(), claimed.attemptId(), completion.questionLogId() != null,
					elapsedMillis(executionStarted));
			if (metrics != null) metrics.counter("chat.worker.completed",
					"status", outcome.isFailed() ? "failed" : "succeeded",
					"code", outcome.isFailed() ? outcome.errorCode().getCode() : "none").increment();
			if (completion.questionLogId() != null) {
				saveResultQuietly(message.jobId(),
						new ChatJobResult(completion.questionLogId(), outcome.store(), outcome.userData()));
			}
			// DB 커밋 뒤에만 ACK함
			ackQuietly(received);
		} catch (CancellationException exception) {
			// 취소가 DB에 확정된 작업만 ACK합니다. 종료·선점 상실의 기존 재발행 흐름은 유지합니다.
			if (jobRepository.findById(message.jobId()).map(found -> found.getStatus().isFinished()).orElse(false)) {
				ackQuietly(received);
			}
			log.info("중단되었거나 실행 권한을 잃은 답변 작업의 결과를 버립니다: 작업ID={}", message.jobId());
		} catch (RuntimeException exception) {
			// DB 장애 등으로 기록 못 함. ACK하지 않아 처리 대기로 남음
			log.error("답변 작업 결과를 기록하지 못해 메시지를 ACK하지 않았습니다: 작업ID={}, 시도ID={}, 예외종류={}",
					message.jobId(), claimed.attemptId(), SafeExceptions.type(exception),
					SafeExceptions.withoutMessages(exception));
		} finally {
			if (metrics != null) metrics.timer("chat.worker.execution").record(
					System.nanoTime() - executionStarted, TimeUnit.NANOSECONDS);
			if (job.computation != null) job.computation.cancel(true);
			runningJobs.remove(job);
		}
	}

	// 진행 안내 저장 실패는 답변 자체를 실패시키지 않음. 소유권은 기존 취소/완료 검사로도 보호됨.
	private void reportPhase(RunningJob job, Phase phase) {
		if (job.lost || Thread.currentThread().isInterrupted()) return;
		try {
			if (queueRepository.updatePhase(job.received.message().jobId(), job.startedAt, phase) > 0) {
				log.debug("답변 작업 진행 단계를 저장했습니다: 작업ID={}, 단계={}", job.received.message().jobId(), phase);
			}
		} catch (RuntimeException exception) {
			log.warn("답변 진행 단계를 저장하지 못했습니다: 작업ID={}, 단계={}, 예외종류={}",
					job.received.message().jobId(), phase, SafeExceptions.type(exception));
		}
	}

	private Completion complete(ChatJobMessage message, LocalDateTime startedAt, ChatJobOutcome outcome) {
		try {
			return completionService.complete(message.jobId(), startedAt, outcome, message.context().region());
		} catch (DataIntegrityViolationException exception) {
			// 다시 실행해도 같은 제약에 걸리는 결과(예: 그새 삭제된 FAQ). LLM을 다시 부르지 않게 저장 실패로 끝냄
			// 연결 장애 같은 다른 오류는 ACK 없이 남김
			log.warn("답변 결과를 저장할 수 없어 실패로 기록합니다: 작업ID={}, 오류코드={}, 예외종류={}",
					message.jobId(), ChatErrorCode.STORAGE_UNAVAILABLE.getCode(), SafeExceptions.type(exception));
			return completionService.complete(message.jobId(), startedAt,
					ChatJobOutcome.failed(ChatErrorCode.STORAGE_UNAVAILABLE), message.context().region());
		}
	}

	private void releaseUnfinished() {
		for (RunningJob job : runningJobs) {
			job.cancel();
			returnToQueue(job.received, job.startedAt);
		}
		jobExecutor.shutdownNow();
	}

	// 그새 다른 실행이 가져갔으면 건드리지 않음
	private void returnToQueue(ReceivedChatJob received, LocalDateTime startedAt) {
		long jobId = received.message().jobId();
		try {
			if (queueRepository.release(jobId, startedAt) > 0) {
				log.info("종료 중이어서 답변 작업을 대기열에 돌려놓았습니다: 작업ID={}", jobId);
				republishQuietly(received);
			}
		} catch (RuntimeException exception) {
			log.warn("답변 작업을 돌려놓지 못했습니다: 작업ID={}, 예외종류={}", jobId, SafeExceptions.type(exception));
		}
	}

	// 새 메시지 발행 뒤 원래 메시지를 ACK함. 발행 실패 시 원래 메시지는 남기고 발행 표시를 지워 재발행되게 함
	private void republishQuietly(ReceivedChatJob received) {
		try {
			stream.publish(received.message());
			stream.ack(received.messageId());
		} catch (RuntimeException exception) {
			clearPublishedQuietly(received.message().jobId());
			log.warn("답변 작업 메시지를 다시 발행하지 못했습니다: 작업ID={}, 예외종류={}", received.message().jobId(), SafeExceptions.type(exception));
		}
	}

	private void clearPublishedQuietly(long jobId) {
		try {
			stream.clearPublished(jobId);
		} catch (RuntimeException exception) {
			log.warn("답변 작업의 발행 표시를 지우지 못했습니다: 작업ID={}, 예외종류={}", jobId, SafeExceptions.type(exception));
		}
	}

	private void ackQuietly(ReceivedChatJob received) {
		try {
			stream.ack(received.messageId());
		} catch (RuntimeException exception) {
			// 다시 전달돼도 작업이 끝나 있으면 실행하지 않고 ACK함
			log.warn("답변 작업 메시지를 ACK하지 못했습니다: 작업ID={}, 메시지ID={}, 예외종류={}",
					received.message().jobId(), received.messageId(), SafeExceptions.type(exception));
		}
	}

	// 결과는 DB에 이미 커밋됐으므로 캐시 저장 실패는 무시함
	private void saveResultQuietly(long jobId, ChatJobResult result) {
		try {
			resultStore.save(jobId, result, properties.resultTtl());
		} catch (RuntimeException exception) {
			log.warn("답변 작업 결과를 Redis에 저장하지 못했습니다: 작업ID={}, 예외종류={}", jobId, SafeExceptions.type(exception));
		}
	}

	private void ensureGroupQuietly() {
		try {
			stream.ensureGroup();
		} catch (RuntimeException exception) {
			log.warn("답변 작업 스트림 그룹을 만들지 못했습니다. 다음 읽기에서 다시 시도합니다: 예외종류={}", SafeExceptions.type(exception));
		}
	}

	// 기다리지 않고 최대 wanted개 잡음
	private int acquireFreeSlots(int wanted) {
		int acquired = 0;
		while (acquired < wanted && slots.tryAcquire()) {
			acquired++;
		}
		return acquired;
	}

	// heartbeat가 이 시각보다 오래된 실행만 멈춘 것으로 봄
	private LocalDateTime stuckBefore(LocalDateTime now) {
		return now.minus(properties.heartbeatTimeout());
	}

	private static LocalDateTime now() {
		return LocalDateTime.now().truncatedTo(ChronoUnit.MICROS);
	}

	private static long elapsedMillis(long startedAt) {
		return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt);
	}

	// 마감까지만 기다림. 남은 시간은 대기 직전에 다시 계산하고, 결과를 받은 뒤에도 같은 마감을 확인함
	// GC·스케줄링 지연으로 마감을 넘겨 돌아온 결과는 저장하지 않음
	static <T> T awaitUntil(Future<T> future, LocalDateTime deadline)
			throws InterruptedException, ExecutionException, TimeoutException {
		long remaining = Duration.between(now(), deadline).toMillis();
		if (remaining <= 0) throw new TimeoutException("답변 처리 제한 시간이 지났습니다.");
		T result = future.get(remaining, TimeUnit.MILLISECONDS);
		if (!now().isBefore(deadline)) throw new TimeoutException("답변 처리 제한 시간이 지났습니다.");
		return result;
	}

	private static void runStep(String name, Runnable step) {
		try {
			step.run();
		} catch (RuntimeException exception) {
			log.warn("답변 작업 정리 중 '{}' 단계에 실패했습니다. 다음 정리에서 다시 시도합니다: 예외종류={}", name, SafeExceptions.type(exception));
		}
	}

	private void sleep(Duration duration) {
		try {
			Thread.sleep(duration);
		} catch (InterruptedException exception) {
			Thread.currentThread().interrupt();
		}
	}

	// startedAt은 선점 시각이자 결과 기록 권한
	private record ClaimedJob(long attemptId, LocalDateTime startedAt, ChatRequestContext context, LocalDateTime deadline) {
	}

	private void renewLeases() {
		for (RunningJob job : runningJobs) {
			if (job.lost) continue;
			try {
				// 갱신된 행이 없으면 다른 실행이 넘겨받은 것임
				if (queueRepository.heartbeat(job.received.message().jobId(), job.startedAt, now()) == 0) {
					count("lease.lost");
					log.warn("답변 작업의 실행 권한을 잃어 계산을 중단합니다: 작업ID={}, 실행시작시각={}, consumer={}",
							job.received.message().jobId(), job.startedAt, properties.consumerName());
					job.cancel();
				}
			} catch (RuntimeException exception) {
				count("heartbeat.failures");
				log.warn("실행 생존 확인 갱신 실패: 작업ID={}, 예외종류={}", job.received.message().jobId(), SafeExceptions.type(exception));
			}
		}
	}

	private static final class RunningJob {
		private final ReceivedChatJob received;
		private final LocalDateTime startedAt;
		private volatile boolean lost;
		private volatile Future<ChatJobOutcome> computation;
		private final List<UnansweredCandidate> unanswered = new CopyOnWriteArrayList<>();

		private void bindComputation(Future<ChatJobOutcome> future) {
			computation = future;
			// cancel()이 Future 연결 전에 실행된 경우에도 중단 신호를 잃지 않습니다.
			if (lost) future.cancel(true);
		}

		private void cancel() {
			lost = true;
			Future<?> current = computation;
			if (current != null) current.cancel(true);
		}

		private RunningJob(ReceivedChatJob received, LocalDateTime startedAt) {
			this.received = received;
			this.startedAt = startedAt;
		}

		private void checkActive() {
			if (lost || Thread.currentThread().isInterrupted()) {
				throw new CancellationException("답변 작업을 계속할 수 없습니다: 작업ID=" + received.message().jobId());
			}
		}
	}
}
