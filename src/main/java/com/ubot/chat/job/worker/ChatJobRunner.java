package com.ubot.chat.job.worker;

import com.ubot.ai.progress.AnswerProgress;
import com.ubot.ai.progress.AnswerProgress.Phase;
import com.ubot.chat.entity.AnswerAttemptsHistory;
import com.ubot.chat.exception.ChatErrorCode;
import com.ubot.chat.job.dto.ChatJobResult;
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
import com.ubot.chat.job.stream.ReceivedChatJob;
import com.ubot.chat.repository.AnswerAttemptsHistoryRepository;
import com.ubot.chat.service.ChatJobOutcome;
import com.ubot.chat.service.ChatJobProcessor;
import com.ubot.common.exception.SafeExceptions;
import com.ubot.llm.client.LlmRecovery;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.ArrayList;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.FutureTask;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.dao.DataIntegrityViolationException;

/** 선점·계산·완료와 실행 슬롯을 소유합니다. 일반 소비와 복구는 같은 슬롯을 사용합니다. */
@Slf4j
public class ChatJobRunner implements AutoCloseable {
	private final ChatJobStream stream;
	private final ChatJobResultStore resultStore;
	private final ChatJobQueueRepository queueRepository;
	private final ChatJobRepository jobRepository;
	private final ChatJobCompletionService completionService;
	private final ChatJobProcessor jobProcessor;
	private final AnswerAttemptsHistoryRepository answerAttemptsHistoryRepository;
	private final ChatWorkerProperties properties;
	private final Semaphore slots;
	private final Set<RunningJob> runningJobs = ConcurrentHashMap.newKeySet();
	private final ExecutorService jobExecutor = Executors.newThreadPerTaskExecutor(
			Thread.ofVirtual().name("chat-job-", 0).factory());
	private final ExecutorService computations = Executors.newVirtualThreadPerTaskExecutor();
	private MeterRegistry metrics;

	public ChatJobRunner(ChatJobStream stream, ChatJobResultStore resultStore,
			ChatJobQueueRepository queueRepository, ChatJobRepository jobRepository,
			ChatJobCompletionService completionService, ChatJobProcessor jobProcessor,
			AnswerAttemptsHistoryRepository answerAttemptsHistoryRepository, ChatWorkerProperties properties) {
		this.stream = stream;
		this.resultStore = resultStore;
		this.queueRepository = queueRepository;
		this.jobRepository = jobRepository;
		this.completionService = completionService;
		this.jobProcessor = jobProcessor;
		this.answerAttemptsHistoryRepository = answerAttemptsHistoryRepository;
		this.properties = properties;
		this.slots = new Semaphore(properties.concurrency());
	}

	void observeWith(MeterRegistry registry) {
		metrics = registry;
		registry.gauge("chat.worker.active", runningJobs, Set::size);
		registry.gauge("chat.jobs.queued", queueRepository, ChatJobQueueRepository::queuedCount);
		registry.gauge("chat.jobs.oldest.queued.seconds", queueRepository, ChatJobQueueRepository::oldestQueuedSeconds);
	}

	int activeCount() { return runningJobs.size(); }

	ExecutionSlot reserve() throws InterruptedException {
		slots.acquire();
		return new ExecutionSlot();
	}

	List<ExecutionSlot> reserveAvailable(int wanted) {
		List<ExecutionSlot> reserved = new ArrayList<>();
		while (reserved.size() < wanted && slots.tryAcquire()) reserved.add(new ExecutionSlot());
		return reserved;
	}

	/** 실행 슬롯을 확보한 후 제출합니다. 반환값은 ACK 시도까지 마친 실행의 완료를 나타냅니다. */
	public CompletableFuture<Void> submit(ReceivedChatJob received) throws InterruptedException {
		return reserve().submit(received);
	}

	/** 제출한 작업만 슬롯을 반환합니다. 제출하지 못한 예약은 호출자가 닫습니다. */
	final class ExecutionSlot implements AutoCloseable {
		private final AtomicBoolean released = new AtomicBoolean();

		CompletableFuture<Void> submit(ReceivedChatJob received) {
			return submitTask(() -> handle(received), () -> republishQuietly(received));
		}

		Optional<CompletableFuture<Void>> submitReclaimed(ReceivedChatJob received, String reason) {
			Optional<ClaimedJob> claimed = takeQuietly(received, true);
			if (claimed.isEmpty()) {
				close();
				return Optional.empty();
			}
			count("reclaimed", "reason", reason);
			return Optional.of(submitTask(() -> execute(received, claimed.get()),
					() -> returnToQueue(received, claimed.get().startedAt())));
		}

		private CompletableFuture<Void> submitTask(Runnable execution, Runnable rejected) {
			try {
				return CompletableFuture.runAsync(() -> {
					try { execution.run(); } finally { close(); }
				}, jobExecutor);
			} catch (RejectedExecutionException exception) {
				close();
				rejected.run();
				return CompletableFuture.completedFuture(null);
			}
		}

		@Override
		public void close() {
			if (released.compareAndSet(false, true)) slots.release();
		}
	}

	void stop(Duration shutdownWait) {
		jobExecutor.shutdown();
		try {
			if (!jobExecutor.awaitTermination(shutdownWait.toMillis(), TimeUnit.MILLISECONDS)) {
				log.warn("종료 대기 시간 안에 끝나지 않은 답변 작업을 대기열에 돌려놓습니다: 작업수={}", runningJobs.size());
				releaseUnfinished();
			}
		} catch (InterruptedException exception) {
			Thread.currentThread().interrupt();
			releaseUnfinished();
		} finally { computations.shutdownNow(); }
	}

	@Override
	public void close() { stop(properties.shutdownWait()); }

	private void count(String name, String... tags) {
		if (metrics != null) metrics.counter("chat.worker." + name, tags).increment();
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

	private void recordQueueWait(ChatJob job, LocalDateTime claimedAt) {
		if (job.getCreatedAt() == null) return;
		Duration waited = Duration.between(job.getCreatedAt(), claimedAt);
		if (!waited.isNegative()) {
			log.debug("답변 작업을 선점했습니다: 작업ID={}, 시도ID={}, 대기시간={}ms, consumer={}",
					job.getId(), job.getAttemptId(), waited.toMillis(), properties.consumerName());
			if (metrics != null) metrics.timer("chat.jobs.queue.wait").record(waited);
		}
	}

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
						new ChatJobResult(completion.questionLogId(), outcome.store(), outcome.userData(),
								outcome.productData()));
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

	private void saveResultQuietly(long jobId, ChatJobResult result) {
		try {
			resultStore.save(jobId, result, properties.resultTtl());
		} catch (RuntimeException exception) {
			log.warn("답변 작업 결과를 Redis에 저장하지 못했습니다: 작업ID={}, 예외종류={}", jobId, SafeExceptions.type(exception));
		}
	}

	private LocalDateTime stuckBefore(LocalDateTime now) {
		return now.minus(properties.heartbeatTimeout());
	}

	private static LocalDateTime now() {
		return LocalDateTime.now().truncatedTo(ChronoUnit.MICROS);
	}

	private static long elapsedMillis(long startedAt) {
		return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt);
	}

	static <T> T awaitUntil(Future<T> future, LocalDateTime deadline)
			throws InterruptedException, ExecutionException, TimeoutException {
		long remaining = Duration.between(now(), deadline).toMillis();
		if (remaining <= 0) throw new TimeoutException("답변 처리 제한 시간이 지났습니다.");
		T result = future.get(remaining, TimeUnit.MILLISECONDS);
		if (!now().isBefore(deadline)) throw new TimeoutException("답변 처리 제한 시간이 지났습니다.");
		return result;
	}

	void renewLeases() {
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

	private record ClaimedJob(long attemptId, LocalDateTime startedAt, ChatRequestContext context, LocalDateTime deadline) {
	}
}
