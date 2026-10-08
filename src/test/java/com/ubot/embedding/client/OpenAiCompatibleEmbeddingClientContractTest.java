package com.ubot.embedding.client;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.web.client.RestClient;

import com.pgvector.PGvector;
import com.ubot.embedding.config.EmbeddingClientFactory;
import com.ubot.embedding.exception.EmbeddingErrorCode;

import tools.jackson.databind.JsonNode;

class OpenAiCompatibleEmbeddingClientContractTest extends EmbeddingClientContractTest {

	@Override
	protected String embeddingPath() {
		return "/v1/embeddings";
	}

	@Override
	protected EmbeddingClient createClient(
	        String serverUrl,
	        String modelName,
	        Duration readTimeout) {

	    return new EmbeddingClientFactory()
	            .createOpenAiCompatible(
	                    RestClient.builder(),
	                    serverUrl + "/v1",
	                    modelName,
	                    Duration.ofSeconds(1),
	                    readTimeout
	            );
	}

	@Override
	protected String embeddingResponse(List<List<Object>> vectors) {
		List<Map<String, Object>> data = new ArrayList<>();
		for (int index = 0; index < vectors.size(); index++) {
			data.add(item(index, vectors.get(index)));
		}
		return response(data);
	}

	@Override
	protected String emptyResponse() {
		return response(List.of());
	}

	@Test
	void sendsOnlyModelAndInput() {
		server().respond(embeddingResponse(List.of(vector(0.1))));

		client().embed("질문");

		// Ollama 전용 옵션은 보내지 않습니다.
		JsonNode request = server().requests().getFirst();
		assertThat(request.propertyNames()).containsExactlyInAnyOrder("model", "input");
	}

	@Test
	void ordersVectorsByIndexOfResponse() {
		// 서버가 입력 순서와 다르게 돌려줘도 index를 보고 입력 순서로 맞춥니다.
		server().respond(response(List.of(item(2, vector(0.3)), item(0, vector(0.1)), item(1, vector(0.2)))));

		List<PGvector> results = client().embedBatch(List.of("질문1", "질문2", "질문3"));

		assertThat(results).extracting(result -> result.toArray()[0]).containsExactly(0.1f, 0.2f, 0.3f);
	}

	@Test
	void usesResponseOrderWhenIndexIsMissing() {
		server().respond(response(List.of(item(null, vector(0.1)), item(null, vector(0.2)))));

		List<PGvector> results = client().embedBatch(List.of("질문1", "질문2"));

		assertThat(results).extracting(result -> result.toArray()[0]).containsExactly(0.1f, 0.2f);
	}

	@ParameterizedTest
	@ValueSource(ints = {0, 2, -1})
	void rejectsIndexThatDoesNotMatchInputs(int secondIndex) {
		// 같은 index가 두 번 오거나 범위를 벗어나면 어느 입력의 벡터인지 알 수 없습니다.
		server().respond(response(List.of(item(0, vector(0.1)), item(secondIndex, vector(0.2)))));

		assertFailure(() -> client().embedBatch(List.of("질문1", "질문2")),
				EmbeddingErrorCode.EMBEDDING_RESPONSE_INVALID);
	}

	@Test
	void callsEmbeddingsPathEvenIfBaseUrlEndsWithSlash() {

	    server().respond(
	            embeddingResponse(List.of(vector(0.1)))
	    );

	    EmbeddingClient client =
	            new EmbeddingClientFactory()
	                    .createOpenAiCompatible(
	                            RestClient.builder(),
	                            server().url() + "/v1/",
	                            MODEL_NAME,
	                            Duration.ofSeconds(1),
	                            Duration.ofSeconds(3)
	                    );

	    assertThat(client.embed("질문").toArray())
	            .hasSize(EmbeddingClient.DIMENSIONS);
	}

	private Map<String, Object> item(Integer index, List<Object> embedding) {
		Map<String, Object> item = new LinkedHashMap<>();
		item.put("object", "embedding");
		if (index != null) {
			item.put("index", index);
		}
		item.put("embedding", embedding);
		return item;
	}

	private String response(List<Map<String, Object>> data) {
		return toJson(Map.of(
				"id", "embd-1",
				"object", "list",
				"model", MODEL_NAME,
				"data", data,
				"usage", Map.of("prompt_tokens", 8, "total_tokens", 8)));
	}
}
