package com.ubot.embedding.worker;

import static org.assertj.core.api.Assertions.assertThat;

import com.pgvector.PGvector;
import com.ubot.embedding.client.EmbeddingClient;
import com.ubot.embedding.config.CounselEmbeddingWorkerConfig;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

class CounselEmbeddingWorkerTest {
	static final GenericContainer<?> REDIS = new GenericContainer<>(DockerImageName.parse("redis:8-alpine")).withExposedPorts(6379);
	static LettuceConnectionFactory connection;
	static StringRedisTemplate redis;
	static final ObjectMapper JSON = new ObjectMapper();
	final AtomicInteger calls = new AtomicInteger();
	volatile CountDownLatch entered = new CountDownLatch(0);
	volatile CountDownLatch release = new CountDownLatch(0);
	CounselEmbeddingWorker worker;

	@BeforeAll static void startRedis() {
		REDIS.start();
		connection = new LettuceConnectionFactory(REDIS.getHost(), REDIS.getMappedPort(6379));
		connection.afterPropertiesSet();
		redis = new StringRedisTemplate(connection);
	}
	@AfterAll static void stopRedis() { connection.destroy(); REDIS.stop(); }
	@BeforeEach void setup() {
		try (var conn = connection.getConnection()) { conn.serverCommands().flushDb(); }
		worker = new CounselEmbeddingWorker(redis, new EmbeddingClient() {
			@Override public String getModelName() { return "test-model"; }
			@Override public List<PGvector> embedBatch(List<String> inputs) {
				calls.incrementAndGet();
				entered.countDown();
				try { release.await(2, TimeUnit.SECONDS); }
				catch (InterruptedException e) { Thread.currentThread().interrupt(); }
				if (inputs.getFirst().equals("failure")) throw new IllegalStateException("not logged");
				float[] vector = new float[1024];
				vector[0] = 1;
				return List.of(new PGvector(vector));
			}
		}, JSON);
	}
	@AfterEach void stopWorker() { release.countDown(); worker.stop(); }

	String enqueue(String input, String model, Duration ttl) {
		String id = UUID.randomUUID().toString();
		redis.opsForValue().set(CounselEmbeddingWorker.PREFIX + "request:" + id,
				JSON.writeValueAsString(Map.of("version", 1, "requestId", id, "input", input, "model", model)), ttl);
		redis.opsForList().rightPush(CounselEmbeddingWorker.QUEUE, id);
		return id;
	}
	JsonNode result(String id) throws Exception {
		long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(4);
		while (System.nanoTime() < deadline) {
			String value = redis.opsForValue().get(CounselEmbeddingWorker.PREFIX + "result:" + id);
			if (value != null) return JSON.readTree(value);
			Thread.sleep(20);
		}
		throw new AssertionError("worker did not return result");
	}

	@Test void embedsOnlyFromCounselQueueWithoutTouchingChatKeys() throws Exception {
		redis.opsForValue().set("chat:jobs:result:sentinel", "existing-result");
		redis.opsForStream().add("chat:jobs", Map.of("jobId", "42"));
		String id = enqueue("요금 문의", "test-model", Duration.ofSeconds(5));
		worker.start();
		var result = result(id);
		assertThat(result.path("status").asText()).isEqualTo("SUCCEEDED");
		assertThat(result.path("embedding").size()).isEqualTo(1024);
		assertThat(calls).hasValue(1);
		assertThat(redis.hasKey(CounselEmbeddingWorker.PREFIX + "request:" + id)).isFalse();
		assertThat(redis.getExpire(CounselEmbeddingWorker.PREFIX + "result:" + id)).isBetween(1L, 10L);
		assertThat(redis.opsForValue().get("chat:jobs:result:sentinel")).isEqualTo("existing-result");
		assertThat(redis.opsForStream().size("chat:jobs")).isEqualTo(1);
	}

	@Test void mismatchedModelNeverCallsEmbedding() throws Exception {
		String id = enqueue("요금 문의", "other-model", Duration.ofSeconds(5));
		worker.start();
		assertThat(result(id).path("errorCode").asText()).isEqualTo("WORKER_MODEL_MISMATCH");
		assertThat(calls).hasValue(0);
	}

	@Test void expiredQueueEntryNeverCallsEmbedding() throws Exception {
		String id = enqueue("expired", "test-model", Duration.ofMillis(30));
		Thread.sleep(80);
		worker.start();
		Thread.sleep(100);
		assertThat(calls).hasValue(0);
		assertThat(redis.hasKey(CounselEmbeddingWorker.PREFIX + "result:" + id)).isFalse();
	}

	@Test void canceledInFlightWorkCannotPublishLateResultAndJobsStaySerial() throws Exception {
		entered = new CountDownLatch(1);
		release = new CountDownLatch(1);
		String first = enqueue("first", "test-model", Duration.ofSeconds(5));
		String second = enqueue("second", "test-model", Duration.ofSeconds(5));
		worker.start();
		assertThat(entered.await(2, TimeUnit.SECONDS)).isTrue();
		assertThat(calls).hasValue(1);
		redis.delete(CounselEmbeddingWorker.PREFIX + "request:" + first);
		release.countDown();
		assertThat(result(second).path("status").asText()).isEqualTo("SUCCEEDED");
		assertThat(redis.hasKey(CounselEmbeddingWorker.PREFIX + "result:" + first)).isFalse();
		assertThat(calls).hasValue(2);
	}

	@Test void oneFailureDoesNotKillWorkerOrRetryTheFailedJob() throws Exception {
		String first = enqueue("failure", "test-model", Duration.ofSeconds(5));
		String second = enqueue("healthy", "test-model", Duration.ofSeconds(5));
		worker.start();
		assertThat(result(first).path("status").asText()).isEqualTo("FAILED");
		assertThat(result(second).path("status").asText()).isEqualTo("SUCCEEDED");
		assertThat(calls).hasValue(2);
	}

	@Test void featureIsAbsentByDefaultAndCannotRunOnApiRole() {
		new ApplicationContextRunner().withUserConfiguration(CounselEmbeddingWorkerConfig.class)
				.withPropertyValues("app.role=worker").run(context ->
						assertThat(context).doesNotHaveBean(CounselEmbeddingWorker.class));
		new ApplicationContextRunner().withUserConfiguration(CounselEmbeddingWorkerConfig.class)
				.withPropertyValues("app.role=api", "app.counsel.embedding-worker.enabled=true").run(context ->
						assertThat(context).doesNotHaveBean(CounselEmbeddingWorker.class));
	}
}
