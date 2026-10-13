package com.ubot.monitoring.operationmonitoring;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.ubot.common.GlobalException;
import com.ubot.monitoring.operationmonitoring.exception.OperationMonitoringErrorCode;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriComponentsBuilder;

@DisplayName("Prometheus 조회 Client 테스트")
class PrometheusQueryClientTest {

    private HttpServer server;
    private PrometheusQueryClient client;
    private int responseStatus;
    private String responseBody;
    private URI requestedUri;

    @BeforeEach
    void setUp() throws IOException {
        responseStatus = 200;
        responseBody = "";
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/", this::respond);
        server.start();
        client = new PrometheusQueryClient(
                RestClient.builder(),
                "http://localhost:" + server.getAddress().getPort(),
                Duration.ofSeconds(1),
                Duration.ofSeconds(1)
        );
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    @Test
    @DisplayName("instant vector 응답을 레이블과 단일 시점 값으로 변환한다")
    void parsesInstantVector() {
        Instant at = Instant.parse("2026-10-12T00:00:00Z");
        responseBody = """
                {"status":"success","data":{"resultType":"vector","result":[
                  {"metric":{"job":"backend","instance":"backend:8080"},"value":[1791763200,"1"]}
                ]}}
                """;

        var result = client.query("up{job=\"backend\"}", at);

        assertThat(requestedUri.getPath()).isEqualTo("/api/v1/query");
        assertThat(queryParameter("query")).isEqualTo("up{job=\"backend\"}");
        assertThat(queryParameter("time")).isEqualTo("1791763200");
        assertThat(result).singleElement().satisfies(series -> {
            assertThat(series.labels()).containsEntry("job", "backend");
            assertThat(series.points()).containsExactly(new PrometheusQueryClient.PrometheusPoint(at, 1.0));
        });
    }

    @Test
    @DisplayName("range matrix 응답에서는 숫자가 아닌 값과 무한대를 제외한다")
    void parsesRangeMatrixAndSkipsInvalidValues() {
        Instant start = Instant.parse("2026-10-12T00:00:00Z");
        Instant end = start.plusSeconds(30);
        responseBody = """
                {"status":"success","data":{"resultType":"matrix","result":[
                  {"metric":{"job":"backend"},"values":[
                    [1791763200,"0.12"], [1791763215,"NaN"], [1791763230,"0.14"]
                  ]}
                ]}}
                """;

        var result = client.queryRange("process_cpu_usage", start, end, Duration.ofSeconds(15));

        assertThat(requestedUri.getPath()).isEqualTo("/api/v1/query_range");
        assertThat(queryParameter("query")).isEqualTo("process_cpu_usage");
        assertThat(queryParameter("start")).isEqualTo("1791763200");
        assertThat(queryParameter("end")).isEqualTo("1791763230");
        assertThat(queryParameter("step")).isEqualTo("15");
        assertThat(result).singleElement().satisfies(series ->
                assertThat(series.points()).containsExactly(
                        new PrometheusQueryClient.PrometheusPoint(start, 0.12),
                        new PrometheusQueryClient.PrometheusPoint(end, 0.14)
                ));
    }

    @Test
    @DisplayName("Prometheus HTTP 오류는 서비스 이용 불가 예외로 변환한다")
    void mapsPrometheusFailureToUnavailableError() {
        responseStatus = 500;

        assertThatThrownBy(() -> client.query("up", Instant.now()))
                .isInstanceOfSatisfying(GlobalException.class,
                        exception -> assertThat(exception.getErrorCode())
                                .isEqualTo(OperationMonitoringErrorCode.PROMETHEUS_UNAVAILABLE));
    }

    private void respond(HttpExchange exchange) throws IOException {
        requestedUri = exchange.getRequestURI();
        byte[] body = responseBody.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(responseStatus, body.length);
        exchange.getResponseBody().write(body);
        exchange.close();
    }

    private String queryParameter(String name) {
        String value = UriComponentsBuilder.fromUri(requestedUri).build().getQueryParams().getFirst(name);
        return URLDecoder.decode(value, StandardCharsets.UTF_8);
    }
}
