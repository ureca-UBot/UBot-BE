package com.ubot.counsel.safety.client;

import com.ubot.counsel.common.config.CounselProperties;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

// 기존 ai-worker와 주고받는 상담 전용 계약 v1. 기존 chat:jobs 키와 채팅 DB는 사용하지 않음
@RequiredArgsConstructor
public class WorkerEmbeddingTransport {
	public static final String PREFIX = "{counsel-embedding}:v1:";
	public static final String QUEUE = PREFIX + "queue";
	private static final RedisScript<Long> PUBLISH = RedisScript.of("""
			if redis.call('LLEN', KEYS[1]) >= 16 then return 0 end
			redis.call('SET', KEYS[2], ARGV[2], 'PX', ARGV[3])
			redis.call('RPUSH', KEYS[1], ARGV[1])
			if redis.call('PTTL', KEYS[1]) < tonumber(ARGV[3]) then
			    redis.call('PEXPIRE', KEYS[1], ARGV[3])
			end
			return 1
			""", Long.class);
	private static final RedisScript<Long> CLEANUP = RedisScript.of("""
			redis.call('LREM', KEYS[1], 0, ARGV[1])
			return redis.call('DEL', KEYS[2], KEYS[3])
			""", Long.class);
	private static final Set<String> WORKER_ERRORS = Set.of(
			"WORKER_MODEL_MISMATCH", "WORKER_INVALID_RESPONSE", "EMBEDDING_TIMEOUT", "EMBEDDING_UNAVAILABLE");

	private final StringRedisTemplate redis;
	private final CounselProperties.Guard config;
	private final ObjectMapper json;


	public JsonNode embed(String normalized) {
		return embed(normalized, System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(config.responseTimeoutMs()));
	}

	public JsonNode embed(String normalized, long deadline) {
		long remainingMs = TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime());
		if (remainingMs <= 0) throw new EmbeddingClient.Failure("WORKER_TIMEOUT");
		String id = UUID.randomUUID().toString();
		String requestKey = PREFIX + "request:" + id;
		String resultKey = PREFIX + "result:" + id;
		try {
			String payload = json.writeValueAsString(Map.of(
					"version", 1, "requestId", id, "input", normalized, "model", config.model()));
			Long accepted = redis.execute(PUBLISH, List.of(QUEUE, requestKey),
					id, payload, String.valueOf(remainingMs));
			if (Long.valueOf(0).equals(accepted)) {
				// 큐가 잠시 찬 경우에만 같은 마감 시간 안에서 한 번 재접수함
				TimeUnit.NANOSECONDS.sleep(Math.min(TimeUnit.MILLISECONDS.toNanos(50),
						Math.max(0, deadline - System.nanoTime())));
				remainingMs = TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime());
				if (remainingMs <= 0) throw new EmbeddingClient.Failure("WORKER_TIMEOUT");
				accepted = redis.execute(PUBLISH, List.of(QUEUE, requestKey), id, payload, String.valueOf(remainingMs));
			}
			if (!Long.valueOf(1).equals(accepted)) throw new EmbeddingClient.Failure("WORKER_BUSY");
			while (System.nanoTime() < deadline) {
				String raw = redis.opsForValue().get(resultKey);
				if (raw != null) {
					if (System.nanoTime() >= deadline) break;
					var result = json.readTree(raw);
					if (result.path("version").asInt() != 1) throw new EmbeddingClient.Failure("WORKER_INVALID_RESPONSE");
					if ("FAILED".equals(result.path("status").asText())) {
						String code = result.path("errorCode").asText();
						throw new EmbeddingClient.Failure(WORKER_ERRORS.contains(code) ? code : "WORKER_INVALID_RESPONSE");
					}
					if (!"SUCCEEDED".equals(result.path("status").asText())
							|| !config.model().equals(result.path("model").asText())) {
						throw new EmbeddingClient.Failure("WORKER_INVALID_RESPONSE");
					}
					return result.path("embedding");
				}
				TimeUnit.NANOSECONDS.sleep(Math.min(TimeUnit.MILLISECONDS.toNanos(50),
						Math.max(0, deadline - System.nanoTime())));
			}
			throw new EmbeddingClient.Failure("WORKER_TIMEOUT");
		} catch (EmbeddingClient.Failure failure) { throw failure; }
		catch (DataAccessException failure) { throw new EmbeddingClient.Failure("WORKER_UNAVAILABLE"); }
		catch (InterruptedException failure) {
			Thread.currentThread().interrupt();
			throw new EmbeddingClient.Failure("EMBEDDING_INTERRUPTED");
		} catch (RuntimeException failure) { throw new EmbeddingClient.Failure("WORKER_INVALID_RESPONSE"); }
		finally {
			try { redis.execute(CLEANUP, List.of(QUEUE, requestKey, resultKey), id); }
			catch (RuntimeException ignored) {
				// Redis가 복구되지 않아도 TTL로 원문·결과가 제거됨
			}
		}
	}
}
