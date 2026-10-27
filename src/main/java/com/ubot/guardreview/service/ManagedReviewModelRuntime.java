package com.ubot.guardreview.service;

import com.ubot.llm.exception.LlmErrorCode;
import com.ubot.llm.exception.LlmException;
import jakarta.annotation.PreDestroy;
import java.net.URI;
import java.net.SocketTimeoutException;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.LongSupplier;
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
import org.springframework.web.client.ResourceAccessException;
import tools.jackson.databind.json.JsonMapper;

/** 모델 서버의 중앙 사용권으로 여러 Worker가 공유하는 컨테이너의 수명을 관리합니다. */
@Component
@Slf4j
public class ManagedReviewModelRuntime implements ReviewModelRuntime {
	private static final JsonMapper JSON = JsonMapper.builder().build();
	private static final int MAX_HEARTBEAT_FAILURES = 3;
	/** 중앙 제어기의 LEASE_SECONDS=900 계약보다 오래 유효하다고 판단하지 않습니다. */
	private static final long MODEL_LEASE_NANOS = Duration.ofSeconds(900).toNanos();
	private final String baseUrl;
	private final String token;
	private final RestClient.Builder builder;
	private final Duration connectTimeout;
	private final Duration readTimeout;
	private final LongSupplier nanoTime;
	private final Map<UUID, Lease> leases = new ConcurrentHashMap<>();
	private final ScheduledExecutorService heartbeats;
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
		this(builder, baseUrl, token, connectTimeout, readTimeout, Executors.newScheduledThreadPool(2, task -> {
			Thread thread = new Thread(task, "guard-review-model-lease");
			thread.setDaemon(true);
			return thread;
		}), System::nanoTime);
	}

	ManagedReviewModelRuntime(RestClient.Builder builder, String baseUrl, String token, Duration connectTimeout,
			Duration readTimeout, ScheduledExecutorService heartbeats, LongSupplier nanoTime) {
		Assert.notNull(builder, "관리자 모델 제어 HTTP 빌더가 필요합니다.");
		Assert.notNull(heartbeats, "관리자 모델 사용권 갱신 스케줄러가 필요합니다.");
		Assert.notNull(nanoTime, "관리자 모델 사용권 확인 시계가 필요합니다.");
		Assert.isTrue(connectTimeout != null && connectTimeout.isPositive(), "연결 제한 시간은 0보다 커야 합니다.");
		Assert.isTrue(readTimeout != null && readTimeout.isPositive(), "응답 제한 시간은 0보다 커야 합니다.");
		this.builder = builder.clone();
		this.baseUrl = baseUrl;
		this.token = token;
		this.connectTimeout = connectTimeout;
		this.readTimeout = readTimeout;
		this.heartbeats = heartbeats;
		this.nanoTime = nanoTime;
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
		try {
			renewWorkLease.run();
			long requestedAt = nanoTime.getAsLong();
			String result = client.put().uri("/leases/{id}", lease.id).body(Map.of("model", ReviewModelClient.MODEL))
					.retrieve().body(String.class);
			var reply = JSON.readTree(result);
			if (!reply.path("ready").asBoolean() || !ReviewModelClient.MODEL.equals(reply.path("model").asString())) {
				throw new IllegalStateException("관리자 전용 모델의 준비 상태가 일치하지 않습니다.");
			}
			lease.lastConfirmedAt = requestedAt;
			lease.ensureValid();
			// 아직 사용권을 준비하는 동안의 응답 지연을 heartbeat 실패로 세지 않습니다.
			lease.heartbeat = heartbeats.scheduleWithFixedDelay(lease::renew, 30, 30, TimeUnit.SECONDS);
			return lease;
		} catch (RuntimeException exception) {
			lease.close();
			if (exception instanceof RestClientResponseException response && response.getStatusCode().value() == 503
					&& response.getResponseBodyAsString().contains("GPU_BUSY"))
				throw new Busy();
			if (transientControlFailure(exception)) {
				LlmErrorCode code = LlmErrorCode.LLM_SERVICE_UNAVAILABLE;
				for (Throwable cause = exception; cause != null; cause = cause.getCause()) {
					if (cause instanceof SocketTimeoutException || cause instanceof HttpTimeoutException) {
						code = LlmErrorCode.LLM_TIMEOUT;
						break;
					}
				}
				if (exception instanceof RestClientResponseException response
						&& (response.getStatusCode().value() == 408 || response.getStatusCode().value() == 504))
					code = LlmErrorCode.LLM_TIMEOUT;
				throw new LlmException(code, exception);
			}
			throw exception;
		}
	}

	private boolean transientControlFailure(RuntimeException exception) {
		if (exception instanceof ResourceAccessException)
			return true;
		if (exception instanceof RestClientResponseException response) {
			int status = response.getStatusCode().value();
			return status == 408 || status == 429 || response.getStatusCode().is5xxServerError();
		}
		return false;
	}

	private final class Lease implements Session {
		private final UUID id;
		private final long runId;
		private final RestClient client;
		private final Runnable renewWorkLease;
		private final AtomicBoolean closed = new AtomicBoolean();
		private volatile RuntimeException failure;
		private volatile long lastConfirmedAt;
		private int consecutiveHeartbeatFailures;
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
			} catch (RuntimeException exception) {
				// 작업 소유권 상실은 제어기와의 일시적인 통신 실패로 취급하지 않습니다.
				invalidate(exception);
				return;
			}
			long requestedAt = nanoTime.getAsLong();
			if (expired(requestedAt)) {
				invalidate(new IllegalStateException("관리자 모델 사용권의 확인된 유효기간이 지났습니다."));
				return;
			}
			try {
				client.post().uri("/leases/{id}/heartbeat", id).retrieve().toBodilessEntity();
				if (!closed.get()) {
					// 요청 시작 시각을 사용해 네트워크 지연만큼 유효기간을 늘리지 않습니다.
					lastConfirmedAt = requestedAt;
					consecutiveHeartbeatFailures = 0;
					if (expired(nanoTime.getAsLong()))
						invalidate(new IllegalStateException("관리자 모델 사용권 갱신 응답이 유효기간을 넘겼습니다."));
				}
			} catch (RuntimeException exception) {
				if (closed.get())
					return;
				consecutiveHeartbeatFailures++;
				if (transientControlFailure(exception) && !expired(nanoTime.getAsLong())
						&& consecutiveHeartbeatFailures < MAX_HEARTBEAT_FAILURES) {
					log.warn("관리자 모델 heartbeat를 다음 주기에 재시도합니다: 실행ID={}, 연속실패수={}, 오류유형={}", runId,
							consecutiveHeartbeatFailures, exception.getClass().getSimpleName());
					return;
				}
				invalidate(exception);
			}
		}

		@Override
		public void ensureValid() {
			if (failure == null && !closed.get() && expired(nanoTime.getAsLong()))
				invalidate(new IllegalStateException("관리자 모델 사용권의 확인된 유효기간이 지났습니다."));
			if (failure != null || closed.get()) {
				throw new IllegalStateException("관리자 분석 작업의 모델 사용권을 잃었습니다.", failure);
			}
		}

		private boolean expired(long now) {
			return now - lastConfirmedAt >= MODEL_LEASE_NANOS;
		}

		private void invalidate(RuntimeException exception) {
			failure = exception;
			close();
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
