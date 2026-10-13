package com.ubot.monitoring.operationmonitoring;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.queryParam;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.ubot.common.GlobalException;
import com.ubot.monitoring.operationmonitoring.exception.OperationMonitoringErrorCode;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

@DisplayName("Prometheus 조회 Client 테스트")
class PrometheusQueryClientTest {

    private final RestClient.Builder builder = RestClient.builder();
    private final MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
    private final PrometheusQueryClient client = new PrometheusQueryClient(builder.build());

    @Test
    @DisplayName("instant vector 응답을 레이블과 단일 시점 값으로 변환한다")
    void parsesInstantVector() {
        Instant at = Instant.parse("2026-10-12T00:00:00Z");
        server.expect(requestTo(containsString("/api/v1/query")))
                .andExpect(queryParam("query", "up%7Bjob%3D%22backend%22%7D"))
                .andExpect(queryParam("time", "1791763200"))
                .andRespond(withSuccess("""
                        {"status":"success","data":{"resultType":"vector","result":[
                          {"metric":{"job":"backend","instance":"backend:8080"},"value":[1791763200,"1"]}
                        ]}}
                        """, MediaType.APPLICATION_JSON));

        var result = client.query("up{job=\"backend\"}", at);

        assertThat(result).singleElement().satisfies(series -> {
            assertThat(series.labels()).containsEntry("job", "backend");
            assertThat(series.points()).containsExactly(new PrometheusQueryClient.PrometheusPoint(at, 1.0));
        });
        server.verify();
    }

    @Test
    @DisplayName("range matrix 응답에서는 숫자가 아닌 값과 무한대를 제외한다")
    void parsesRangeMatrixAndSkipsInvalidValues() {
        Instant start = Instant.parse("2026-10-12T00:00:00Z");
        Instant end = start.plusSeconds(30);
        server.expect(requestTo(containsString("/api/v1/query_range")))
                .andExpect(queryParam("query", "process_cpu_usage"))
                .andExpect(queryParam("start", "1791763200"))
                .andExpect(queryParam("end", "1791763230"))
                .andExpect(queryParam("step", "15"))
                .andRespond(withSuccess("""
                        {"status":"success","data":{"resultType":"matrix","result":[
                          {"metric":{"job":"backend"},"values":[
                            [1791763200,"0.12"], [1791763215,"NaN"], [1791763230,"0.14"]
                          ]}
                        ]}}
                        """, MediaType.APPLICATION_JSON));

        var result = client.queryRange("process_cpu_usage", start, end, Duration.ofSeconds(15));

        assertThat(result).singleElement().satisfies(series ->
                assertThat(series.points()).containsExactly(
                        new PrometheusQueryClient.PrometheusPoint(start, 0.12),
                        new PrometheusQueryClient.PrometheusPoint(end, 0.14)
                ));
        server.verify();
    }

    @Test
    @DisplayName("Prometheus HTTP 오류는 서비스 이용 불가 예외로 변환한다")
    void mapsPrometheusFailureToUnavailableError() {
        server.expect(requestTo(containsString("/api/v1/query")))
                .andRespond(withServerError());

        assertThatThrownBy(() -> client.query("up", Instant.now()))
                .isInstanceOfSatisfying(GlobalException.class,
                        exception -> assertThat(exception.getErrorCode())
                                .isEqualTo(OperationMonitoringErrorCode.PROMETHEUS_UNAVAILABLE));
        server.verify();
    }
}
