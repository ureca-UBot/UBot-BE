package com.ubot.counsel;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.pgvector.PGvector;
import com.ubot.counsel.common.config.CounselProperties;
import com.ubot.counsel.safety.client.EmbeddingClient;
import com.ubot.counsel.safety.client.WorkerEmbeddingTransport;
import com.ubot.counsel.safety.repository.SafetyRepository;
import com.ubot.counsel.safety.service.SafetyGuardService;
import com.ubot.embedding.worker.CounselEmbeddingWorker;
import com.ubot.forbiddenword.entity.ForbiddenWord;
import com.ubot.forbiddenword.enums.ForbiddenWordStatus;
import com.ubot.forbiddenword.repository.ForbiddenWordRepository;
import com.ubot.forbiddenword.service.ForbiddenWordFilterService;
import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;
import tools.jackson.databind.ObjectMapper;

class WorkerEmbeddingTransportTest {
	static final GenericContainer<?> REDIS = new GenericContainer<>(DockerImageName.parse("redis:8-alpine")).withExposedPorts(6379);
	static final ObjectMapper JSON = new ObjectMapper();
	static LettuceConnectionFactory connection;
	static StringRedisTemplate redis;
	WorkerEmbeddingTransport transport;

	@BeforeAll static void start() {
		REDIS.start();
		connection = new LettuceConnectionFactory(REDIS.getHost(), REDIS.getMappedPort(6379));
		connection.afterPropertiesSet();
		redis = new StringRedisTemplate(connection);
	}
	@AfterAll static void stop() { connection.destroy(); REDIS.stop(); }
	@BeforeEach void reset() {
		try (var conn = connection.getConnection()) { conn.serverCommands().flushDb(); }
		transport = new WorkerEmbeddingTransport(redis,
				new CounselProperties.Guard("http://unused", "test-model", "test-v1", 0.95, 3, 2, 500, 1000), JSON);
	}

	CompletableFuture<String> reply(Map<String, Object> response) {
		return CompletableFuture.supplyAsync(() -> {
			String id = redis.opsForList().leftPop(WorkerEmbeddingTransport.QUEUE, Duration.ofSeconds(3));
			assertThat(id).isNotNull();
			String requestKey = WorkerEmbeddingTransport.PREFIX + "request:" + id;
			var request = JSON.readTree(redis.opsForValue().get(requestKey));
			assertThat(request.path("version").asInt()).isEqualTo(1);
			assertThat(request.path("requestId").asText()).isEqualTo(id);
			assertThat(request.path("model").asText()).isEqualTo("test-model");
			assertThat(request.path("input").asText()).isEqualTo("요금 문의");
			assertThat(redis.getExpire(requestKey, TimeUnit.MILLISECONDS)).isBetween(1L, 1000L);
			redis.opsForValue().set(WorkerEmbeddingTransport.PREFIX + "result:" + id,
					JSON.writeValueAsString(response), Duration.ofSeconds(10));
			return id;
		});
	}

	@Test void exchangesVersionedPayloadAndCleansUpWithoutChangingChatQueue() throws Exception {
		redis.opsForStream().add("chat:jobs", Map.of("jobId", "42"));
		var values = new java.util.ArrayList<>(Collections.nCopies(1024, 0.0));
		values.set(0, 1.0);
		var job = reply(Map.of("version", 1, "status", "SUCCEEDED", "model", "test-model", "embedding", values));
		assertThat(transport.embed("요금 문의").size()).isEqualTo(1024);
		String id = job.get(3, TimeUnit.SECONDS);
		assertThat(redis.hasKey(WorkerEmbeddingTransport.PREFIX + "request:" + id)).isFalse();
		assertThat(redis.hasKey(WorkerEmbeddingTransport.PREFIX + "result:" + id)).isFalse();
		assertThat(redis.opsForStream().size("chat:jobs")).isEqualTo(1);
	}

	@Test void existingFilterPreventsQueueWorkAndOnlyPassedMessagesReachWorker() {
		var words = mock(ForbiddenWordRepository.class);
		when(words.findAllByStatus(ForbiddenWordStatus.ACTIVE)).thenReturn(List.of(
				ForbiddenWord.builder().word("필터검증").status(ForbiddenWordStatus.ACTIVE).build()));
		var filter = new ForbiddenWordFilterService(words);
		filter.refreshForbiddenWordCache();
		var beans = new DefaultListableBeanFactory();
		beans.registerSingleton("workerTransport", transport);
		var properties = new CounselProperties(true, 30, 120, 15, 60, 30,
				new CounselProperties.Guard("http://127.0.0.1:1/unused", "test-model", "test-v1", 0.95, 3, 2, 500, 1000));
		var embedding = new EmbeddingClient(properties, JSON, beans.getBeanProvider(WorkerEmbeddingTransport.class), "worker");
		var safety = mock(SafetyRepository.class);
		when(safety.findTopMatches(anyString())).thenReturn(List.of(new SafetyRepository.Match(1, 0.2, "INSULT")));
		var guard = new SafetyGuardService(filter, embedding, safety, properties);
		var model = mock(com.ubot.embedding.client.EmbeddingClient.class);
		when(model.getModelName()).thenReturn("test-model");
		float[] values = new float[com.ubot.embedding.client.EmbeddingClient.DIMENSIONS];
		values[0] = 1;
		when(model.embed("요금 문의")).thenReturn(new PGvector(values));
		var worker = new CounselEmbeddingWorker(redis, model, JSON);
		redis.opsForStream().add("chat:jobs", Map.of("jobId", "untouched"));
		worker.start();
		try {
			assertThat(guard.checkMessage("필터검증 문의").status()).isEqualTo("BLOCKED");
			verifyNoInteractions(model, safety);
			assertThat(redis.keys(WorkerEmbeddingTransport.PREFIX + "*")).isEmpty();

			assertThat(guard.checkMessage("  요금　문의  ").status()).isEqualTo("ALLOWED");
			verify(model).embed("요금 문의");
			assertThat(redis.keys(WorkerEmbeddingTransport.PREFIX + "*")).isEmpty();
			assertThat(redis.opsForStream().size("chat:jobs")).isEqualTo(1);
		} finally {
			worker.stop();
		}
	}

	@Test void noWorkerTimesOutAndRemovesQueuedOriginal() {
		long start = System.nanoTime();
		assertThatThrownBy(() -> transport.embed("요금 문의"))
				.isInstanceOf(EmbeddingClient.Failure.class).hasMessage("WORKER_TIMEOUT");
		assertThat(Duration.ofNanos(System.nanoTime() - start).toMillis()).isLessThan(2500);
		assertThat(redis.keys(WorkerEmbeddingTransport.PREFIX + "*")).isEmpty();
	}

	@Test void fullQueueIsRejectedWithoutDroppingOtherWork() {
		List<String> pending = java.util.stream.IntStream.range(0, 16).mapToObj(i -> UUID.randomUUID().toString()).toList();
		redis.opsForList().rightPushAll(WorkerEmbeddingTransport.QUEUE, pending);
		assertThatThrownBy(() -> transport.embed("요금 문의"))
				.isInstanceOf(EmbeddingClient.Failure.class).hasMessage("WORKER_BUSY");
		assertThat(redis.opsForList().range(WorkerEmbeddingTransport.QUEUE, 0, -1)).containsExactlyElementsOf(pending);
		assertThat(redis.keys(WorkerEmbeddingTransport.PREFIX + "request:*")).isEmpty();
	}

	@Test void workerFailureRemainsFailureAndNeverFallsBackToHttp() throws Exception {
		var job = reply(Map.of("version", 1, "status", "FAILED", "model", "test-model", "errorCode", "EMBEDDING_UNAVAILABLE"));
		assertThatThrownBy(() -> transport.embed("요금 문의"))
				.isInstanceOf(EmbeddingClient.Failure.class).hasMessage("EMBEDDING_UNAVAILABLE");
		job.get(3, TimeUnit.SECONDS);
		assertThat(redis.keys(WorkerEmbeddingTransport.PREFIX + "*")).isEmpty();
	}

	@Test void wrongModelCannotBeComparedWithExistingSafetyProfile() throws Exception {
		var job = reply(Map.of("version", 1, "status", "SUCCEEDED", "model", "other-model", "embedding", List.of(1)));
		assertThatThrownBy(() -> transport.embed("요금 문의"))
				.isInstanceOf(EmbeddingClient.Failure.class).hasMessage("WORKER_INVALID_RESPONSE");
		job.get(3, TimeUnit.SECONDS);
	}

	@Test void wrongProtocolAndUnknownErrorAreRejected() throws Exception {
		var job = reply(Map.of("version", 2, "status", "SUCCEEDED", "model", "test-model"));
		assertThatThrownBy(() -> transport.embed("요금 문의")).hasMessage("WORKER_INVALID_RESPONSE");
		job.get(3, TimeUnit.SECONDS);
		var unknown = reply(Map.of("version", 1, "status", "FAILED", "errorCode", "unknown upstream content"));
		assertThatThrownBy(() -> transport.embed("요금 문의")).hasMessage("WORKER_INVALID_RESPONSE");
		unknown.get(3, TimeUnit.SECONDS);
	}
}
