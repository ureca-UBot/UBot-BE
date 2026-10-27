package com.ubot.embedding.client;

import com.pgvector.PGvector;
import com.ubot.embedding.exception.EmbeddingErrorCode;
import com.ubot.embedding.exception.EmbeddingException;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

/**
 * OpenAI 호환 embeddings API를 호출합니다. 운영 환경의 vLLM에 연결할 때 씁니다.
 * 오류 변환 기준은 OllamaEmbeddingClient와 같습니다.
 */
@Slf4j
public class OpenAiCompatibleEmbeddingClient implements EmbeddingClient {

	private static final String EMBEDDINGS_PATH = "/embeddings";
	private static final int MAX_LOGGED_BODY_LENGTH = 300;

	private final RestClient restClient;
	private final String modelName;

	/** restClient의 기본 주소는 OpenAI 호환 API 주소입니다. 예: http://localhost:8001/v1 */
	public OpenAiCompatibleEmbeddingClient(RestClient restClient, String modelName) {
		this.restClient = restClient;
		this.modelName = modelName;
	}

	@Override
	public List<PGvector> embedBatch(List<String> texts) {
		Map<String, Object> response = callServer(Map.of("model", modelName, "input", texts));
		if (response == null || !(response.get("data") instanceof List<?> data) || data.isEmpty()) {
			throw EmbeddingVectors.invalidResponse();
		}
		return EmbeddingVectors.toPGvectors(orderByIndex(data), texts.size());
	}

	@Override
	public String getModelName() {
		return modelName;
	}

	/** 응답 항목을 index 순서로 놓아 입력 순서와 맞춥니다. index가 없는 항목은 응답에 나온 자리를 씁니다. */
	private List<Object> orderByIndex(List<?> data) {
		Object[] embeddings = new Object[data.size()];
		for (int position = 0; position < data.size(); position++) {
			if (!(data.get(position) instanceof Map<?, ?> item) || item.get("embedding") == null) {
				throw EmbeddingVectors.invalidResponse();
			}
			int index = item.get("index") instanceof Number number ? number.intValue() : position;
			// 범위를 벗어나거나 같은 index가 두 번 오면 어느 입력의 벡터인지 알 수 없습니다.
			if (index < 0 || index >= embeddings.length || embeddings[index] != null) {
				throw EmbeddingVectors.invalidResponse();
			}
			embeddings[index] = item.get("embedding");
		}
		return Arrays.asList(embeddings);
	}

	@SuppressWarnings("unchecked") // JSON 객체를 Map으로 받습니다. 값의 타입은 꺼낼 때 확인합니다.
	private Map<String, Object> callServer(Map<String, Object> requestBody) {
		try {
			return restClient.post()
					.uri(EMBEDDINGS_PATH)
					.body(requestBody)
					.retrieve()
					.body(Map.class);
		} catch (ResourceAccessException exception) {
			throw new EmbeddingException(EmbeddingErrorCode.EMBEDDING_TIMEOUT);
		} catch (RestClientResponseException exception) {
			// 모델 이름이 다르거나 입력이 너무 긴 경우처럼 서버가 거절한 이유를 확인할 수 있게 남깁니다.
			log.warn("임베딩 서버가 오류 응답을 반환했습니다: 상태코드={}, 응답={}",
					exception.getStatusCode().value(), abbreviate(exception.getResponseBodyAsString()));
			throw new EmbeddingException(EmbeddingErrorCode.EMBEDDING_SERVICE_UNAVAILABLE);
		} catch (RestClientException exception) {
			throw new EmbeddingException(EmbeddingErrorCode.EMBEDDING_SERVICE_UNAVAILABLE);
		}
	}

	private String abbreviate(String body) {
		String singleLine = body.replaceAll("\\s+", " ");
		return singleLine.length() <= MAX_LOGGED_BODY_LENGTH
				? singleLine
				: singleLine.substring(0, MAX_LOGGED_BODY_LENGTH) + "...";
	}
}
