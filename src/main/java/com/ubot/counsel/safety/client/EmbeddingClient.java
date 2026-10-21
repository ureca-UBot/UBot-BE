package com.ubot.counsel.safety.client;

import com.ubot.counsel.common.config.CounselProperties;
import com.ubot.counsel.safety.util.SafetyTextNormalizer;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import static com.ubot.embedding.client.EmbeddingClient.DIMENSIONS;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Component("counselGuardEmbeddingClient")
public class EmbeddingClient {
	public static class Failure extends RuntimeException {
		private final String code;
		public Failure(String code) { super(code);
		this.code = code; }
		public String code() {
			return code;
		}
	}
	private final CounselProperties.Guard config;
	private final ObjectMapper json;
	private final HttpClient http;
	private final Semaphore slots;
	private final WorkerEmbeddingTransport worker;
	public EmbeddingClient(CounselProperties properties, ObjectMapper json, ObjectProvider<WorkerEmbeddingTransport> worker,
			@Value("${counsel.guard.transport:worker}") String transport) {
		if (!java.util.Set.of("http", "worker").contains(transport)) {
			throw new IllegalArgumentException("counsel.guard.transport must be http or worker");
		}
		this.config = properties.guard();
		this.json = json;
		this.worker = transport.equals("worker") ? worker.getObject() : null;
		slots = new Semaphore(config.concurrency(), true);
		http = HttpClient.newBuilder().connectTimeout(Duration.ofMillis(config.connectTimeoutMs()))
				.followRedirects(HttpClient.Redirect.NEVER).build();
	}
	public String embed(String input) {
		long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(config.responseTimeoutMs());
		boolean acquired = false;
		CompletableFuture<HttpResponse<String>> pending = null;
		try {
			acquired = slots.tryAcquire(config.responseTimeoutMs(), TimeUnit.MILLISECONDS);
			if (!acquired) throw new Failure("GUARD_BUSY");
			if (worker != null) return vectorLiteral(worker.embed(SafetyTextNormalizer.normalize(input), deadline));
			String body = json.writeValueAsString(Map.of("model", config.model(), "input", SafetyTextNormalizer.normalize(input)));
			long remaining = deadline - System.nanoTime();
			if (remaining <= 0) throw new Failure("EMBEDDING_TIMEOUT");
			pending = http.sendAsync(HttpRequest.newBuilder(URI.create(config.embeddingUrl()))
					.timeout(Duration.ofNanos(remaining))
					.header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body)).build(),
					HttpResponse.BodyHandlers.ofString());
			// 대기 시간과 응답 본문 수신까지 하나의 제한 시간을 공유함
			var response = pending.get(Math.max(0, deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
			if (response.statusCode() != 200) throw new Failure("EMBEDDING_HTTP");
			var root = json.readTree(response.body());
			var data = root.path("data");
			if (!data.isArray() || data.size() != 1) throw new Failure("EMBEDDING_SHAPE");
			return vectorLiteral(data.get(0).path("embedding"));
		} catch (Failure e) { throw e; }
		catch (TimeoutException e) { throw new Failure("EMBEDDING_TIMEOUT"); }
		catch (java.util.concurrent.ExecutionException e) {
			throw new Failure(e.getCause() instanceof HttpTimeoutException ? "EMBEDDING_TIMEOUT" : "EMBEDDING_UNAVAILABLE");
		}
		catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new Failure("EMBEDDING_INTERRUPTED"); }
		catch (Exception e) { throw new Failure("EMBEDDING_UNAVAILABLE"); }
		finally {
			if (pending != null && !pending.isDone()) pending.cancel(true);
			if (acquired) slots.release();
		}
	}

	private String vectorLiteral(JsonNode vector) {
		if (!vector.isArray() || vector.size() != DIMENSIONS) throw new Failure("EMBEDDING_DIMENSION");
		double norm = 0;
		StringBuilder literal = new StringBuilder("[");
		for (int i = 0; i < DIMENSIONS; i++) {
			if (!vector.get(i).isNumber()) throw new Failure("EMBEDDING_VALUE");
			float value = (float) vector.get(i).asDouble();
			if (!Float.isFinite(value)) throw new Failure("EMBEDDING_VALUE");
			norm += (double) value * value;
			if (i > 0) literal.append(',');
			literal.append(value);
		}
		if (norm == 0 || !Double.isFinite(norm)) throw new Failure("EMBEDDING_ZERO");
		return literal.append(']').toString();
	}
}
