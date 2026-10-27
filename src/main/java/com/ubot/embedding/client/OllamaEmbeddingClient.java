package com.ubot.embedding.client;

import com.pgvector.PGvector;
import com.ubot.embedding.exception.EmbeddingErrorCode;
import com.ubot.embedding.exception.EmbeddingException;
import java.util.List;
import java.util.Map;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/** Ollama /api/embed를 호출합니다. 요청 형식과 오류 변환은 기존 EmbeddingService와 같습니다. */
public class OllamaEmbeddingClient implements EmbeddingClient {

	private static final String EMBED_PATH = "/api/embed";

	// 기존에 보내던 요청 옵션입니다. 값을 바꾸면 긴 입력의 벡터가 달라질 수 있어 그대로 둡니다.
	private static final int NUM_CTX = 4096;
	private static final String KEEP_ALIVE = "30m";

	private final RestClient restClient;
	private final String modelName;

	/** restClient의 기본 주소는 Ollama 서버 주소입니다. 예: http://localhost:11435 */
	public OllamaEmbeddingClient(RestClient restClient, String modelName) {
		this.restClient = restClient;
		this.modelName = modelName;
	}

	@Override
	public List<PGvector> embedBatch(List<String> texts) {
		Map<String, Object> requestBody = Map.of(
				"model", modelName,
				"input", texts,
				"options", Map.of("num_ctx", NUM_CTX),
				"keep_alive", KEEP_ALIVE);

		Map<String, Object> response = callOllama(requestBody);
		if (response == null
				|| !(response.get("embeddings") instanceof List<?> embeddings) || embeddings.isEmpty()) {
			throw EmbeddingVectors.invalidResponse();
		}
		return EmbeddingVectors.toPGvectors(embeddings, texts.size());
	}

	@Override
	public String getModelName() {
		return modelName;
	}

	@SuppressWarnings("unchecked") // JSON 객체를 Map으로 받습니다. 값의 타입은 꺼낼 때 확인합니다.
	private Map<String, Object> callOllama(Map<String, Object> requestBody) {
		try {
			return restClient.post()
					.uri(EMBED_PATH)
					.body(requestBody)
					.retrieve()
					.body(Map.class);
		} catch (ResourceAccessException exception) {
			throw new EmbeddingException(EmbeddingErrorCode.EMBEDDING_TIMEOUT);
		} catch (RestClientException exception) {
			throw new EmbeddingException(EmbeddingErrorCode.EMBEDDING_SERVICE_UNAVAILABLE);
		}
	}
}
