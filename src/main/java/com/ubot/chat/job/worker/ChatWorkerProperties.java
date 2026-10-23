package com.ubot.chat.job.worker;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.StringUtils;

// app.chat.worker.* 설정. 각 항목은 docs/reference/configuration.md 참고
@ConfigurationProperties("app.chat.worker")
public record ChatWorkerProperties(
		int concurrency,
		boolean pollingEnabled,
		Duration readBlock,
		String consumerName,
		Duration maxQueueWait,
		Duration republishAfter,
		Duration reclaimIdle,
		int maxDeliveries,
		Duration recoveryInterval,
		Duration resultTtl,
		Duration executionTimeout,
		Duration heartbeatTimeout,
		Duration shutdownWait,
		Duration modelRetryBackoff
) {
	// 기본값은 application.yml이 정함. 테스트는 모든 값을 직접 넘김
	public ChatWorkerProperties {
		requirePositive("execution-timeout", executionTimeout);
		requirePositive("heartbeat-timeout", heartbeatTimeout);
		requirePositive("shutdown-wait", shutdownWait);
		requirePositive("model-retry-backoff", modelRetryBackoff);
		if (concurrency < 1) {
			throw new IllegalArgumentException("app.chat.worker.concurrency는 1 이상이어야 합니다: " + concurrency);
		}
		if (maxDeliveries < 1) {
			throw new IllegalArgumentException("app.chat.worker.max-deliveries는 1 이상이어야 합니다: " + maxDeliveries);
		}
		if (!StringUtils.hasText(consumerName)) {
			throw new IllegalArgumentException("app.chat.worker.consumer-name이 비어 있습니다.");
		}
		requirePositive("read-block", readBlock);
		requirePositive("max-queue-wait", maxQueueWait);
		requirePositive("republish-after", republishAfter);
		requirePositive("reclaim-idle", reclaimIdle);
		requirePositive("recovery-interval", recoveryInterval);
		requirePositive("result-ttl", resultTtl);
		if (republishAfter.compareTo(maxQueueWait) >= 0) {
			throw new IllegalArgumentException("app.chat.worker.republish-after(" + republishAfter
					+ ")는 max-queue-wait(" + maxQueueWait + ")보다 짧아야 합니다.");
		}
		if (heartbeatTimeout.compareTo(reclaimIdle) > 0) {
			throw new IllegalArgumentException("app.chat.worker.heartbeat-timeout(" + heartbeatTimeout
					+ ")는 reclaim-idle(" + reclaimIdle + ")보다 길 수 없습니다.");
		}
	}

	private static void requirePositive(String name, Duration value) {
		if (value == null || value.isZero() || value.isNegative()) {
			throw new IllegalArgumentException("app.chat.worker." + name + "는 0보다 커야 합니다: " + value);
		}
	}
}
