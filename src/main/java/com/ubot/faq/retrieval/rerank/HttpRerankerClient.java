package com.ubot.faq.retrieval.rerank;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

import lombok.extern.slf4j.Slf4j;

/**
 * OpenAI 호환 서버의 rerank API를 호출합니다. vLLM이 제공하는 형식입니다.
 * 요청: {"model", "query", "documents": [문자열]}
 * 응답: {"results": [{"index": 입력 자리, "relevance_score": 점수}]}
 */
@Slf4j
public class HttpRerankerClient implements RerankerClient {

    private static final String RERANK_PATH = "/v1/rerank";
    private static final int MAX_LOGGED_BODY_LENGTH = 300;

    private final RestClient restClient;
    private final String modelName;

    /** restClient의 기본 주소는 Reranker 서버 주소입니다. 예: http://localhost:8002 */
    public HttpRerankerClient(RestClient restClient, String modelName) {
        this.restClient = restClient;
        this.modelName = modelName;
    }

    @Override
    public List<Double> score(String query, List<String> documents) {
        Map<String, Object> response = callServer(Map.of(
                "model", modelName,
                "query", query,
                "documents", documents
        ));
        if (response == null || !(response.get("results") instanceof List<?> results)) {
            throw new RerankException("Reranker 응답에 results가 없습니다.");
        }
        return orderByIndex(results, documents.size());
    }

    /** 응답 항목을 index 순서로 놓아 입력 순서와 맞춥니다. 빠지거나 겹친 index, 유한하지 않은 점수는 받지 않습니다. */
    private List<Double> orderByIndex(List<?> results, int expectedSize) {
        if (results.size() != expectedSize) {
            throw new RerankException(
                    "Reranker 응답 개수가 요청과 다릅니다: 요청=" + expectedSize + ", 응답=" + results.size());
        }
        Double[] scores = new Double[expectedSize];
        for (Object result : results) {
            if (!(result instanceof Map<?, ?> item)
                    || !(item.get("index") instanceof Number indexValue)
                    || !(item.get("relevance_score") instanceof Number scoreValue)) {
                throw new RerankException("Reranker 응답 항목의 형식이 올바르지 않습니다.");
            }
            int index = indexValue.intValue();
            double score = scoreValue.doubleValue();
            if (index < 0 || index >= expectedSize || scores[index] != null) {
                throw new RerankException("Reranker 응답의 index가 범위를 벗어나거나 겹칩니다: " + index);
            }
            if (!Double.isFinite(score)) {
                throw new RerankException("Reranker 점수가 유한한 수가 아닙니다: index=" + index);
            }
            scores[index] = score;
        }
        return Arrays.asList(scores);
    }

    @SuppressWarnings("unchecked") // JSON 객체를 Map으로 받습니다. 값의 타입은 꺼낼 때 확인합니다.
    private Map<String, Object> callServer(Map<String, Object> requestBody) {
        try {
            return restClient.post()
                    .uri(RERANK_PATH)
                    .body(requestBody)
                    .retrieve()
                    .body(Map.class);
        } catch (ResourceAccessException exception) {
            throw new RerankException("Reranker 서버에 연결하지 못했거나 응답이 제한 시간을 넘었습니다.", exception);
        } catch (RestClientResponseException exception) {
            log.warn("Reranker 서버가 오류 응답을 반환했습니다: 상태코드={}, 응답={}",
                    exception.getStatusCode().value(), abbreviate(exception.getResponseBodyAsString()));
            throw new RerankException(
                    "Reranker 서버가 오류 응답을 반환했습니다: " + exception.getStatusCode().value(), exception);
        } catch (RestClientException exception) {
            throw new RerankException("Reranker 응답을 읽지 못했습니다.", exception);
        }
    }

    private String abbreviate(String body) {
        String singleLine = body.replaceAll("\\s+", " ");
        return singleLine.length() <= MAX_LOGGED_BODY_LENGTH
                ? singleLine
                : singleLine.substring(0, MAX_LOGGED_BODY_LENGTH) + "...";
    }
}
