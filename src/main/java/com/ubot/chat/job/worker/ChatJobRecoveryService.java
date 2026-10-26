package com.ubot.chat.job.worker;

import java.util.concurrent.CompletableFuture;
import java.util.ArrayList;
import com.ubot.chat.exception.ChatErrorCode;
import com.ubot.chat.job.dto.ChatRequestContext;
import com.ubot.chat.job.entity.ChatJob;
import com.ubot.chat.job.entity.ChatJobStatus;
import com.ubot.chat.job.repository.ChatJobQueueRepository;
import com.ubot.chat.job.repository.ChatJobRepository;
import com.ubot.chat.job.service.ChatJobCompletionService;
import com.ubot.chat.job.stream.ChatJobMessage;
import com.ubot.chat.job.stream.ChatJobStream;
import com.ubot.chat.job.stream.PendingChatJob;
import com.ubot.chat.job.stream.ReceivedChatJob;
import com.ubot.common.exception.SafeExceptions;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;

/** 대기 만료, 재발행, 멈춘 실행과 유실 메시지의 복구 정책을 담당합니다. */
@Slf4j
public class ChatJobRecoveryService {
	private static final int RECOVERY_BATCH = 100;
	private static final int REPUBLISH_SCAN_LIMIT = 1000;
	private static final int EXPIRE_SCAN_LIMIT = 1000;
	private static final int REPUBLISH_LIMIT = 1000;
	private final ChatJobStream stream;
	private final ChatJobQueueRepository queueRepository;
	private final ChatJobRepository jobRepository;
	private final ChatJobCompletionService completionService;
	private final ChatWorkerProperties properties;
	private final ChatJobRunner runner;
	private MeterRegistry metrics;

	public ChatJobRecoveryService(ChatJobStream stream, ChatJobQueueRepository queueRepository,
			ChatJobRepository jobRepository, ChatJobCompletionService completionService,
			ChatWorkerProperties properties, ChatJobRunner runner) {
		this.stream = stream;
		this.queueRepository = queueRepository;
		this.jobRepository = jobRepository;
		this.completionService = completionService;
		this.properties = properties;
		this.runner = runner;
	}

	void observeWith(MeterRegistry registry) { metrics = registry; }

	/** 각 정리 단계를 격리하며, 반환값은 이번 복구에서 제출한 실행들의 완료를 나타냅니다. */
	public CompletableFuture<Void> recover() {
		var executions = new ArrayList<CompletableFuture<Void>>();
		runStep("대기 한도를 넘긴 작업 정리", this::expireQueued);
		runStep("발행되지 않은 작업 다시 발행", this::republishUnpublished);
		runStep("heartbeat가 끊긴 실행 넘겨받기", () -> reclaimSilentRuns(executions));
		runStep("방치된 메시지 넘겨받기", () -> reclaimStale(executions));
		runStep("메시지를 잃은 작업 정리", this::failOrphaned);
		runStep("스트림 정리", this::trimAcknowledged);
		return CompletableFuture.allOf(executions.toArray(CompletableFuture[]::new));
	}

	private void count(String name, String... tags) {
		if (metrics != null) metrics.counter("chat.worker." + name, tags).increment();
	}

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

	private void reclaimSilentRuns(List<CompletableFuture<Void>> executions) {
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
		int started = reclaim(silent, properties.heartbeatTimeout(), "heartbeat", executions);
		if (started > 0) {
			log.warn("heartbeat가 끊긴 답변 작업의 메시지를 넘겨받아 다시 실행합니다: 작업수={}", started);
		}
	}

	private boolean isNotRunningLive(long jobId, LocalDateTime silentBefore) {
		return jobRepository.findById(jobId)
				.map(job -> job.getStatus() != ChatJobStatus.RUNNING || lastSign(job).isBefore(silentBefore))
				.orElse(true);
	}

	private static LocalDateTime lastSign(ChatJob job) {
		LocalDateTime sign = job.getHeartbeatAt() != null ? job.getHeartbeatAt() : job.getStartedAt();
		return sign == null ? LocalDateTime.MIN : sign;
	}

	private void reclaimStale(List<CompletableFuture<Void>> executions) {
		int started = reclaim(stream.findStale(properties.reclaimIdle(), RECOVERY_BATCH), properties.reclaimIdle(), "idle", executions);
		if (started > 0) {
			log.warn("오래 처리되지 않은 답변 작업 메시지를 넘겨받아 다시 실행합니다: 작업수={}", started);
		}
	}

	private int reclaim(List<PendingChatJob> stale, Duration minIdle, String reason, List<CompletableFuture<Void>> executions) {
		failExhausted(stale.stream()
				.filter(pending -> pending.deliveryCount() >= properties.maxDeliveries())
				.map(PendingChatJob::messageId)
				.toList(), minIdle);
		List<String> retryable = stale.stream()
				.filter(pending -> pending.deliveryCount() < properties.maxDeliveries())
				.map(PendingChatJob::messageId)
				.toList();
		List<ChatJobRunner.ExecutionSlot> reserved = runner.reserveAvailable(retryable.size());
		int used = 0;
		int submittedBefore = executions.size();
		try {
			if (reserved.isEmpty()) return 0;
			for (ReceivedChatJob received : stream.claim(properties.consumerName(), minIdle,
					retryable.subList(0, reserved.size()))) {
				reserved.get(used++).submitReclaimed(received, reason).ifPresent(executions::add);
			}
		} finally {
			for (int i = used; i < reserved.size(); i++) reserved.get(i).close();
		}
		return executions.size() - submittedBefore;
	}

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

	private void ackQuietly(ReceivedChatJob received) {
		try {
			stream.ack(received.messageId());
		} catch (RuntimeException exception) {
			// 다시 전달돼도 작업이 끝나 있으면 실행하지 않고 ACK함
			log.warn("답변 작업 메시지를 ACK하지 못했습니다: 작업ID={}, 메시지ID={}, 예외종류={}",
					received.message().jobId(), received.messageId(), SafeExceptions.type(exception));
		}
	}

	private LocalDateTime stuckBefore(LocalDateTime now) {
		return now.minus(properties.heartbeatTimeout());
	}

	private static LocalDateTime now() {
		return LocalDateTime.now().truncatedTo(ChronoUnit.MICROS);
	}

	private static void runStep(String name, Runnable step) {
		try {
			step.run();
		} catch (RuntimeException exception) {
			log.warn("답변 작업 정리 중 '{}' 단계에 실패했습니다. 다음 정리에서 다시 시도합니다: 예외종류={}", name, SafeExceptions.type(exception));
		}
	}
}
