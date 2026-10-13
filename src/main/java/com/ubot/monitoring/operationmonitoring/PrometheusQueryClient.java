package com.ubot.monitoring.operationmonitoring;

import java.time.Duration;
import java.time.Instant;
import java.net.http.HttpClient;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.ubot.monitoring.operationmonitoring.exception.OperationMonitoringErrorCode;
import com.ubot.monitoring.operationmonitoring.exception.OperationMonitoringException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import tools.jackson.databind.JsonNode;

/** Prometheus HTTP API에 대해 서버 내부에서만 고정 PromQL을 실행합니다. */
@Component
@Slf4j
public class PrometheusQueryClient {

    private final RestClient restClient;

    public PrometheusQueryClient(
            RestClient.Builder restClientBuilder,
            @Value("${operation-monitoring.prometheus.base-url}") String baseUrl,
            @Value("${operation-monitoring.prometheus.connect-timeout:2s}") Duration connectTimeout,
            @Value("${operation-monitoring.prometheus.read-timeout:10s}") Duration readTimeout
    ) {
        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(connectTimeout)
                .build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(readTimeout);
        this.restClient = restClientBuilder
                .baseUrl(baseUrl)
                .requestFactory(requestFactory)
                .build();
    }

    public List<PrometheusSeries> query(String query, Instant at) {
        JsonNode response = request("/api/v1/query", Map.of(
                "query", query,
                "time", String.valueOf(at.getEpochSecond())
        ));
        return toSeries(response.path("data").path("result"), false);
    }

    public List<PrometheusSeries> queryRange(String query, Instant start, Instant end, Duration step) {
        JsonNode response = request("/api/v1/query_range", Map.of(
                "query", query,
                "start", String.valueOf(start.getEpochSecond()),
                "end", String.valueOf(end.getEpochSecond()),
                "step", String.valueOf(step.toSeconds())
        ));
        return toSeries(response.path("data").path("result"), true);
    }

    private JsonNode request(String path, Map<String, String> queryParameters) {
        try {
            JsonNode response = restClient.get()
                    .uri(uriBuilder -> {
                        uriBuilder.path(path);
                        queryParameters.forEach((name, ignored) -> uriBuilder.queryParam(name, "{" + name + "}"));
                        return uriBuilder.build(queryParameters);
                    })
                    .retrieve()
                    .body(JsonNode.class);
            if (response == null || !"success".equals(response.path("status").asString())) {
                log.warn(
                        "Prometheus가 비정상 응답을 반환했습니다. path={}, errorType={}, error={}",
                        path,
                        response == null ? null : response.path("errorType").asString(),
                        response == null ? null : response.path("error").asString()
                );
                throw new OperationMonitoringException(OperationMonitoringErrorCode.PROMETHEUS_UNAVAILABLE);
            }
            return response;
        } catch (RestClientException exception) {
            log.warn("Prometheus 조회에 실패했습니다. path={}", path, exception);
            throw new OperationMonitoringException(OperationMonitoringErrorCode.PROMETHEUS_UNAVAILABLE);
        }
    }

    private List<PrometheusSeries> toSeries(JsonNode result, boolean range) {
        List<PrometheusSeries> series = new ArrayList<>();
        if (!result.isArray()) {
            return series;
        }
        for (JsonNode item : result) {
            Map<String, String> labels = new LinkedHashMap<>();
            for (Map.Entry<String, JsonNode> entry : item.path("metric").properties()) {
                labels.put(entry.getKey(), entry.getValue().asString());
            }
            JsonNode values = range ? item.path("values") : item.path("value");
            series.add(new PrometheusSeries(labels, range ? toPoints(values) : toSinglePoint(values)));
        }
        return series;
    }

    private List<PrometheusPoint> toSinglePoint(JsonNode value) {
        return toPoint(value).map(List::of).orElseGet(List::of);
    }

    private List<PrometheusPoint> toPoints(JsonNode values) {
        if (!values.isArray()) {
            return List.of();
        }
        List<PrometheusPoint> points = new ArrayList<>();
        for (JsonNode value : values) {
            toPoint(value).ifPresent(points::add);
        }
        return points;
    }

    private java.util.Optional<PrometheusPoint> toPoint(JsonNode value) {
        if (!value.isArray() || value.size() < 2) {
            return java.util.Optional.empty();
        }
        try {
            double timestamp = value.get(0).asDouble();
            String rawValue = value.get(1).asString();
            if (!StringUtils.hasText(rawValue)) {
                return java.util.Optional.empty();
            }
            double numericValue = Double.parseDouble(rawValue);
            return Double.isFinite(numericValue)
                    ? java.util.Optional.of(new PrometheusPoint(Instant.ofEpochMilli((long) (timestamp * 1000)), numericValue))
                    : java.util.Optional.empty();
        } catch (NumberFormatException exception) {
            return java.util.Optional.empty();
        }
    }

    public record PrometheusSeries(Map<String, String> labels, List<PrometheusPoint> points) {
    }

    public record PrometheusPoint(Instant at, double value) {
    }
}
