package com.ubot.guardreview.service;

import jakarta.annotation.PreDestroy;
import java.net.URI;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.util.Assert;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import tools.jackson.databind.json.JsonMapper;

/** 모델 서버의 중앙 사용권으로 여러 Worker가 공유하는 컨테이너의 수명을 관리합니다. */
@Component
@Slf4j
public class ManagedReviewModelRuntime implements ReviewModelRuntime {
	private static final JsonMapper JSON = JsonMapper.builder().build();
	private final String baseUrl;
	private final String token;
	private final RestClient.Builder builder;
	private final Duration connectTimeout;
	private final Duration readTimeout;
	private final Map<UUID, Lease> leases = new ConcurrentHashMap<>();
	private final ScheduledExecutorService heartbeats = Executors.newScheduledThreadPool(2, task -> {
		Thread thread = new Thread(task, "guard-review-model-lease");
		thread.setDaemon(true);
		return thread;
	});
	private final AtomicBoolean shuttingDown = new AtomicBoolean();

	public ManagedReviewModelRuntime(RestClient.Builder builder, String baseUrl, String token) {
		this(builder, baseUrl, token, Duration.ofSeconds(3), Duration.ofSeconds(300));
	}

	@Autowired
	public ManagedReviewModelRuntime(RestClient.Builder builder,
			@Value("${app.guard-review.runtime.base-url:${GUARD_REVIEW_RUNTIME_BASE_URL:}}") String baseUrl,
			@Value("${app.guard-review.runtime.token:${GUARD_REVIEW_RUNTIME_TOKEN:}}") String token,
			@Value("${app.guard-review.runtime.connect-timeout:${GUARD_REVIEW_RUNTIME_CONNECT_TIMEOUT:3s}}") Duration connectTimeout,
			@Value("${app.guard-review.runtime.read-timeout:${GUARD_REVIEW_RUNTIME_READ_TIMEOUT:300s}}") Duration readTimeout) {
		Assert.notNull(builder, "관리자 모델 제어 HTTP 빌더가 필요합니다.");
		Assert.isTrue(connectTimeout != null && connectTimeout.isPositive(), "연결 제한 시간은 0보다 커야 합니다.");
		Assert.isTrue(readTimeout != null && readTimeout.isPositive(), "응답 제한 시간은 0보다 커야 합니다.");
		this.builder = builder.clone();
		this.baseUrl = baseUrl;
		this.token = token;
		this.connectTimeout = connectTimeout;
		this.readTimeout = readTimeout;
	}

	@Override
	public Session acquire(long runId, Runnable renewWorkLease) {
		if (shuttingDown.get())
			throw new IllegalStateException("관리자 모델 제어가 종료 중입니다.");
		if (!StringUtils.hasText(baseUrl) || !StringUtils.hasText(token)) {
			throw new IllegalStateException("관리자 전용 모델 실행 제어 주소와 인증 설정이 필요합니다.");
		}
		URI endpoint = URI.create(baseUrl.strip());
		if (!("http".equals(endpoint.getScheme()) || "https".equals(endpoint.getScheme())) || endpoint.getHost() == null
				|| endpoint.getRawUserInfo() != null || endpoint.getRawQuery() != null
				|| endpoint.getRawFragment() != null) {
			throw new IllegalStateException("관리자 모델 실행 제어 주소를 확인해 주세요.");
		}
		var factory = new SimpleClientHttpRequestFactory();
		factory.setConnectTimeout(connectTimeout);
		factory.setReadTimeout(readTimeout);
		RestClient client = builder.clone().baseUrl(baseUrl.strip().replaceAll("/+$", ""))
				.defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + token).requestFactory(factory).build();
		Lease lease = new Lease(UUID.randomUUID(), runId, client, renewWorkLease);
		leases.put(lease.id, lease);
		lease.heartbeat = heartbeats.scheduleWithFixedDelay(lease::renew, 30, 30, TimeUnit.SECONDS);
		try {
			renewWorkLease.run();
			String result = client.put().uri("/leases/{id}", lease.id).body(Map.of("model", ReviewModelClient.MODEL))
					.retrieve().body(String.class);
			var reply = JSON.readTree(result);
			if (!reply.path("ready").asBoolean() || !ReviewModelClient.MODEL.equals(reply.path("model").asString())) {
				throw new IllegalStateException("관리자 전용 모델의 준비 상태가 일치하지 않습니다.");
			}
			lease.ensureValid();
			return lease;
		} catch (RuntimeException exception) {
			lease.close();
			if (exception instanceof RestClientResponseException response && response.getStatusCode().value() == 503
					&& response.getResponseBodyAsString().contains("GPU_BUSY"))
				throw new Busy();
			throw exception;
		}
	}

	private final class Lease implements Session {
		private final UUID id;
		private final long runId;
		private final RestClient client;
		private final Runnable renewWorkLease;
		private final AtomicBoolean closed = new AtomicBoolean();
		private volatile RuntimeException failure;
		private ScheduledFuture<?> heartbeat;

		Lease(UUID id, long runId, RestClient client, Runnable renewWorkLease) {
			this.id = id;
			this.runId = runId;
			this.client = client;
			this.renewWorkLease = renewWorkLease;
		}

		void renew() {
			if (closed.get())
				return;
			try {
				renewWorkLease.run();
				client.post().uri("/leases/{id}/heartbeat", id).retrieve().toBodilessEntity();
			} catch (RuntimeException exception) {
				failure = exception;
				close();
			}
		}

		@Override
		public void ensureValid() {
			if (failure != null || closed.get()) {
				throw new IllegalStateException("관리자 분석 작업의 모델 사용권을 잃었습니다.", failure);
			}
		}

		@Override
		public void close() {
			if (closed.compareAndSet(false, true)) {
				if (heartbeat != null)
					heartbeat.cancel(false);
				leases.remove(id);
			}
			try {
				client.delete().uri("/leases/{id}", id).retrieve().toBodilessEntity();
			} catch (RuntimeException exception) {
				// 중앙 제어기가 사용권 만료 시에도 전용 모델을 내립니다. 다른 작업의 사용권은 유지합니다.
				log.warn("관리자 모델 사용권 해제를 다시 확인해야 합니다: 실행ID={}, 오류유형={}", runId, exception.getClass().getSimpleName());
			}
		}
	}

	@PreDestroy
	public void close() {
		shuttingDown.set(true);
		for (Lease lease : leases.values())
			lease.close();
		heartbeats.shutdownNow();
	}
}
