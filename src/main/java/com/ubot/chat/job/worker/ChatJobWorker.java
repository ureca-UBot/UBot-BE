package com.ubot.chat.job.worker;

import com.ubot.chat.job.stream.ChatJobStream;
import com.ubot.chat.job.stream.ReceivedChatJob;
import com.ubot.common.exception.SafeExceptions;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.Executors;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.SmartLifecycle;

/** 스트림 소비와 생존 확인·복구 스케줄의 수명 주기만 관리합니다. */
@Slf4j
public class ChatJobWorker implements SmartLifecycle {
	private final ChatJobStream stream;
	private final ChatWorkerProperties properties;
	private final ChatJobRunner runner;
	private final ChatJobRecoveryService recovery;
	private final ScheduledExecutorService leases = Executors.newSingleThreadScheduledExecutor(
			Thread.ofPlatform().name("chat-job-lease").daemon().factory());
	private final ScheduledExecutorService timers = Executors.newSingleThreadScheduledExecutor(
			Thread.ofPlatform().name("chat-job-timer").daemon().factory());
	private volatile boolean running;
	private Thread poller;
	private volatile long lastRecoveryNanos;
	private String streamReadFailure;

	public ChatJobWorker(ChatJobStream stream, ChatWorkerProperties properties,
			ChatJobRunner runner, ChatJobRecoveryService recovery) {
		this.stream = stream;
		this.properties = properties;
		this.runner = runner;
		this.recovery = recovery;
	}

	void observeWith(MeterRegistry registry) {
		registry.gauge("chat.worker.ready", this, worker -> worker.isOperational() ? 1 : 0);
	}

	private void recover() {
		recovery.recover();
		lastRecoveryNanos = System.nanoTime();
	}

	public boolean isOperational() {
		return running && properties.pollingEnabled() && poller != null && poller.isAlive()
				&& System.nanoTime() - lastRecoveryNanos < properties.recoveryInterval().multipliedBy(3).toNanos();
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
		leases.scheduleWithFixedDelay(runner::renewLeases, heartbeatMillis, heartbeatMillis, TimeUnit.MILLISECONDS);
		long recoveryMillis = properties.recoveryInterval().toMillis();
		timers.scheduleWithFixedDelay(this::recover, recoveryMillis, recoveryMillis, TimeUnit.MILLISECONDS);
		poller = Thread.ofPlatform().name("chat-job-poller").start(this::pollLoop);
		log.info("답변 Worker를 시작했습니다: 동시실행수={}, consumer={}, 대기한도={}, 넘겨받기기준={}, 최대전달횟수={}, 실행한도={}, 모델재시도간격={}",
				properties.concurrency(), properties.consumerName(), properties.maxQueueWait(),
				properties.reclaimIdle(), properties.maxDeliveries(), properties.executionTimeout(), properties.modelRetryBackoff());
	}

	@Override
	public void stop() {
		log.info("답변 Worker를 종료합니다: consumer={}, 실행중작업수={}, 종료대기={}",
				properties.consumerName(), runner.activeCount(), properties.shutdownWait());
		running = false;
		if (poller != null) {
			poller.interrupt();
		}
		timers.shutdownNow();
		runner.stop(properties.shutdownWait());
		leases.shutdownNow();
		log.info("답변 Worker 종료 처리를 마쳤습니다: consumer={}", properties.consumerName());
	}

	@Override
	public boolean isRunning() {
		return running;
	}

	private void pollLoop() {
		while (running) {
			ChatJobRunner.ExecutionSlot slot;
			try {
				slot = runner.reserve();
			} catch (InterruptedException exception) {
				return;
			}
			if (!running) {
				slot.close();
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
					slot.submit(received.getFirst());
					started = true;
				}
			} catch (RuntimeException exception) {
				handleReadFailure(exception);
			} finally {
				if (!started) {
					slot.close();
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

	private void ensureGroupQuietly() {
		try {
			stream.ensureGroup();
		} catch (RuntimeException exception) {
			log.warn("답변 작업 스트림 그룹을 만들지 못했습니다. 다음 읽기에서 다시 시도합니다: 예외종류={}", SafeExceptions.type(exception));
		}
	}

	private void sleep(Duration duration) {
		try {
			Thread.sleep(duration);
		} catch (InterruptedException exception) {
			Thread.currentThread().interrupt();
		}
	}
}
