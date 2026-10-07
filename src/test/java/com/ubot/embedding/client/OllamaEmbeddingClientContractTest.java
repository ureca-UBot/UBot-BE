package com.ubot.embedding.client;

import static org.assertj.core.api.Assertions.assertThat;

import com.ubot.embedding.config.EmbeddingConfig;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;

/** OllamaEmbeddingClient가 공통 계약을 지키는지 Ollama의 /api/embed 응답 형식으로 확인합니다. */
class OllamaEmbeddingClientContractTest extends EmbeddingClientContractTest {

	@Override
	protected String embeddingPath() {
		return "/api/embed";
	}

	@Override
	protected EmbeddingClient createClient(String serverUrl, String modelName, Duration readTimeout) {
		return new EmbeddingConfig().ollamaEmbeddingClient(RestClient.builder(), serverUrl, modelName,
				Duration.ofSeconds(1), readTimeout);
	}

	@Override
	protected String embeddingResponse(List<List<Object>> vectors) {
		return toJson(Map.of(
				"model", MODEL_NAME,
				"embeddings", vectors,
				"total_duration", 14143917,
				"load_duration", 1019500,
				"prompt_eval_count", 8));
	}

	@Override
	protected String emptyResponse() {
		return toJson(Map.of("model", MODEL_NAME, "embeddings", List.of()));
	}

	@Test
	void keepsRequestOptionsOfPreviousImplementation() {
		server().respond(embeddingResponse(List.of(vector(0.1))));

		client().embed("질문");

		// EmbeddingService가 직접 호출하던 때와 같은 옵션을 보냅니다.
		JsonNode request = server().requests().getFirst();
		assertThat(request.get("options").get("num_ctx").asInt()).isEqualTo(4096);
		assertThat(request.get("keep_alive").asString()).isEqualTo("30m");
	}
}
