package com.ubot.counsel.safety.client;

import com.ubot.counsel.common.config.CounselProperties;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** 기존 ai-worker와 주고받는 상담 전용 계약 v1. 기존 chat:jobs 키와 채팅 DB는 사용하지 않습니다. */
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

	public WorkerEmbeddingTransport(StringRedisTemplate redis, CounselProperties.Guard config, ObjectMapper json) {
		this.redis = redis;
		this.config = config;
		this.json = json;
	}

	public JsonNode embed(String normalized) {
		String id = UUID.randomUUID().toString();
		String requestKey = PREFIX + "request:" + id;
		String resultKey = PREFIX + "result:" + id;
		long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(config.responseTimeoutMs());
		try {
			String payload = json.writeValueAsString(Map.of(
					"version", 1, "requestId", id, "input", normalized, "model", config.model()));
			Long accepted = redis.execute(PUBLISH, List.of(QUEUE, requestKey),
					id, payload, String.valueOf(config.responseTimeoutMs()));
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
				Thread.sleep(50);
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
			catch (RuntimeException ignored) { /* Redis가 복구되지 않아도 TTL로 원문·결과가 제거됩니다. */ }
		}
	}
}
