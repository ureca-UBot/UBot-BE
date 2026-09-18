package com.ubot.embedding.service;

import com.ubot.common.ErrorCode;
import com.ubot.embedding.exception.EmbeddingException;
import com.pgvector.PGvector;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.List;
import java.util.Map;

@Service
public class EmbeddingService {

    private final RestClient restClient;
    private final String modelName;

    // 생성자
    public EmbeddingService(RestClient.Builder restClientBuilder, // http 요청 용도
            @Value("${ollama.base-url}") String baseUrl, // Ollama 서버 주소 (Spring AI를 쓰지 않고 직접 호출)
            @Value("${ollama.embedding.model}") String modelName, // 모델명
            @Value("${ollama.connect-timeout:3s}") Duration connectTimeout, // 연결 timeout
            @Value("${ollama.read-timeout:10s}") Duration readTimeout) { // 응답 timeout

        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(connectTimeout)
                .build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(readTimeout);
        this.restClient = restClientBuilder
                .baseUrl(baseUrl)
                .requestFactory(requestFactory)
                .build();
        this.modelName = modelName;
    }

    // 단일 텍스트 변환
    // 아래의 embedTexts 재사용
    public PGvector embedText(String text) {
        return embedTexts(List.of(text)).get(0);
    }

    public List<PGvector> embedTexts(List<String> texts) {

        // Ollama 요청
        Map<String, Object> requestBody = Map.of(
                "model", modelName,
                "input", texts,
                "options", Map.of("num_ctx", 4096),
                "keep_alive", "30m");

        Map<String, Object> response = callOllama(requestBody); // Ollama 전체 응답
        if (response == null) {
            throw new EmbeddingException(ErrorCode.EMBEDDING_RESPONSE_INVALID);
        }

        Object embeddingsRaw = response.get("embeddings"); // 응답에서 벡터 추출
        if (!(embeddingsRaw instanceof List<?> embeddings) || embeddings.isEmpty()) {
            throw new EmbeddingException(ErrorCode.EMBEDDING_RESPONSE_INVALID);
        }

        @SuppressWarnings("unchecked") // 형 변환 경고 무시. 위에서 List 타입인지 이미 확인했으므로 안전한 형변환
        List<List<Double>> vectors = (List<List<Double>>) embeddingsRaw;
        return vectors.stream().map(this::toPGvector).toList();
    }

    private Map<String, Object> callOllama(Map<String, Object> requestBody) {
        try {
            return restClient.post()
                    .uri("/api/embed")
                    .body(requestBody)
                    .retrieve()
                    .body(Map.class);
        } catch (ResourceAccessException e) {
            throw new EmbeddingException(ErrorCode.EMBEDDING_TIMEOUT);
        } catch (RestClientException e) {
            throw new EmbeddingException(ErrorCode.EMBEDDING_SERVICE_UNAVAILABLE);
        }
    }

    private PGvector toPGvector(List<Double> vector) {
        float[] result = new float[vector.size()];
        for (int i = 0; i < vector.size(); i++) {
            result[i] = vector.get(i).floatValue();
        }
        return new PGvector(result);
    }
}