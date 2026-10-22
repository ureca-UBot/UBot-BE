package com.ubot.embedding.worker;

import com.pgvector.PGvector;
import com.ubot.embedding.client.EmbeddingClient;
import com.ubot.embedding.exception.EmbeddingErrorCode;
import com.ubot.embedding.exception.EmbeddingException;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Metrics;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.context.SmartLifecycle;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import tools.jackson.databind.ObjectMapper;

// 상담 임베딩만 처리함. chat:jobs, 채팅 DB, FAQ 검색, LLM 답변 생성은 사용하지 않음
// 요청 원문과 결과는 짧은 TTL로만 Redis에 보관하고, 실패한 작업은 자동 재실행하지 않음
public class CounselEmbeddingWorker implements SmartLifecycle {
	public static final String PREFIX = "{counsel-embedding}:v1:";
	public static final String QUEUE = PREFIX + "queue";
	private static final RedisScript<Long> COMPLETE = RedisScript.of("""
			if redis.call('EXISTS', KEYS[1]) == 0 then return 0 end
			redis.call('SET', KEYS[2], ARGV[1], 'PX', 10000)
			redis.call('DEL', KEYS[1])
			return 1
			""", Long.class);

	private final StringRedisTemplate redis;
	private final EmbeddingClient embedding;
	private final ObjectMapper json;
	private final MeterRegistry metrics;
	private final AtomicBoolean running = new AtomicBoolean();
	private volatile Thread thread;

	// 지표를 보지 않는 테스트용. 전역 레지스트리는 등록된 저장소가 없으면 기록하지 않음
	public CounselEmbeddingWorker(StringRedisTemplate redis, EmbeddingClient embedding, ObjectMapper json) {
		this(redis, embedding, json, Metrics.globalRegistry);
	}

	public CounselEmbeddingWorker(StringRedisTemplate redis, EmbeddingClient embedding, ObjectMapper json,
			MeterRegistry metrics) {
		this.redis = redis;
		this.embedding = embedding;
		this.json = json;
		this.metrics = metrics;
	}

	@Override
	public synchronized void start() {
		if (!running.compareAndSet(false, true)) return;
		// 상담 호출은 한 스레드에서 순차 처리하며 기존 ChatJobWorker의 실행 풀을 빌리지 않음
		thread = Thread.ofPlatform().daemon().name("counsel-embedding-worker").start(this::consume);
	}

	private void consume() {
		while (running.get()) {
			try {
				String id = redis.opsForList().leftPop(QUEUE, Duration.ofSeconds(1));
				if (id != null) process(id);
			} catch (RuntimeException ignored) {
				// Redis 장애 시 바쁜 반복을 막음. 요청 원문·토큰·벡터는 로그에 남기지 않음
				if (running.get()) {
					try { Thread.sleep(1000); }
					catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); break; }
				}
			}
		}
	}

	private void process(String id) {
		try {
			if (!UUID.fromString(id).toString().equals(id)) return;
		} catch (IllegalArgumentException ignored) { return; }
		String requestKey = PREFIX + "request:" + id;
		String raw = redis.opsForValue().get(requestKey);
		// 대기 중 만료됐거나 호출자가 취소한 작업임
		if (raw == null) {
			count("expired", "none");
			return;
		}
		Map<String, Object> result;
		try {
			if (raw.length() > 16000) throw new IllegalArgumentException();
			var request = json.readTree(raw);
			if (request.path("version").asInt() != 1 || !id.equals(request.path("requestId").asText())
					|| !request.path("input").isString() || request.path("input").asText().isBlank()
					|| request.path("input").asText().length() > 2000) {
				throw new IllegalArgumentException();
			}
			if (!embedding.getModelName().equals(request.path("model").asText())) {
				result = failed("WORKER_MODEL_MISMATCH");
			} else {
				PGvector vector = embedding.embed(request.path("input").asText());
				float[] values = vector.toArray();
				double norm = 0;
				if (values.length != EmbeddingClient.DIMENSIONS) throw new IllegalArgumentException();
				for (float value : values) {
					if (!Float.isFinite(value)) throw new IllegalArgumentException();
					norm += (double) value * value;
				}
				if (norm == 0 || !Double.isFinite(norm)) throw new IllegalArgumentException();
				result = Map.of("version", 1, "status", "SUCCEEDED", "model", embedding.getModelName(), "embedding", values);
			}
		} catch (EmbeddingException error) {
			result = failed(error.getErrorCode() == EmbeddingErrorCode.EMBEDDING_TIMEOUT
					? "EMBEDDING_TIMEOUT" : "EMBEDDING_UNAVAILABLE");
		} catch (RuntimeException error) {
			result = failed("WORKER_INVALID_RESPONSE");
		}
		count(((String) result.get("status")).toLowerCase(Locale.ROOT), (String) result.getOrDefault("errorCode", "none"));
		// 계산 중 요청이 만료·취소되면 늦은 결과를 게시하지 않음
		if (running.get()) redis.execute(COMPLETE, List.of(requestKey, PREFIX + "result:" + id),
				json.writeValueAsString(result));
	}

	// code는 실패 코드 4종(EMBEDDING_TIMEOUT 등) 또는 none
	private void count(String status, String code) {
		metrics.counter("counsel.embedding.jobs", "status", status, "code", code).increment();
	}

	private Map<String, Object> failed(String code) {
		return Map.of("version", 1, "status", "FAILED", "model", embedding.getModelName(), "errorCode", code);
	}

	@Override
	public synchronized void stop() {
		running.set(false);
		if (thread != null) {
			thread.interrupt();
			try { thread.join(1000); }
			catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
		}
	}

	@Override
	public void stop(Runnable callback) {
		stop();
		callback.run();
	}

	@Override
	public boolean isRunning() {
		return running.get() && thread != null && thread.isAlive();
	}
}
