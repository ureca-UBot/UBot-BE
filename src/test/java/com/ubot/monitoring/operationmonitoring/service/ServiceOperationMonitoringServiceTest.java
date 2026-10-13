package com.ubot.monitoring.operationmonitoring.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.ubot.monitoring.operationmonitoring.PrometheusQueryClient;
import com.ubot.monitoring.operationmonitoring.PrometheusQueryClient.PrometheusPoint;
import com.ubot.monitoring.operationmonitoring.PrometheusQueryClient.PrometheusSeries;
import com.ubot.monitoring.operationmonitoring.ServiceMonitoringRange;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
@DisplayName("서비스 운영 모니터링 Service 테스트")
class ServiceOperationMonitoringServiceTest {

    private static final Instant AT = Instant.parse("2026-10-13T00:00:00Z");

    @Mock
    private PrometheusQueryClient prometheusQueryClient;

    @InjectMocks
    private ServiceOperationMonitoringService service;

    @Test
    @DisplayName("개요는 Prometheus 결과를 단일 GC 시계열을 포함한 응답으로 조합한다")
    void assemblesOverview() {
        PrometheusSeries point = series(Map.of(), 1.0);
        when(prometheusQueryClient.query(any(), any())).thenReturn(List.of(point));
        when(prometheusQueryClient.queryRange(any(), any(), any(), any())).thenAnswer(invocation -> {
            String query = invocation.getArgument(0);
            Map<String, String> labels = query.contains("container_label_com_docker_compose_service")
                    ? Map.of("container_label_com_docker_compose_service", "backend")
                    : Map.of();
            return List.of(series(labels, 1.0));
        });

        var result = service.getOverview(ServiceMonitoringRange.ONE_HOUR);

        assertThat(result.window().range()).isEqualTo("1h");
        assertThat(result.services().backend()).isEqualTo(
                com.ubot.monitoring.operationmonitoring.dto.ServiceMonitoringOverviewResponse.ServiceStatus.UP);
        assertThat(result.summary().apiRps()).isEqualTo(1.0);
        assertThat(result.series().backend().gcPauseSecondsPerSecond()).hasSize(1);
        assertThat(result.series().containers().cpuUsageRate()).containsKey("backend");

        ArgumentCaptor<String> queryCaptor = ArgumentCaptor.forClass(String.class);
        verify(prometheusQueryClient, atLeastOnce()).queryRange(queryCaptor.capture(), any(), any(), any());
        assertThat(queryCaptor.getAllValues())
                .contains("sum(rate(jvm_gc_pause_seconds_sum[5m]))");
    }

    @Test
    @DisplayName("엔드포인트별 요청 수, RPS, 오류율, 백분위 응답 시간을 계산한다")
    void assemblesEndpointMetrics() {
        when(prometheusQueryClient.query(any(), any())).thenAnswer(invocation -> {
            String query = invocation.getArgument(0);
            if (query.contains("status=~\"4..\"")) {
                return List.of(series(Map.of("uri", "/chat/questions"), 6.0));
            }
            if (query.contains("status=~\"5..\"")) {
                return List.of(series(Map.of("uri", "/chat/questions"), 3.0));
            }
            if (query.contains("histogram_quantile(0.5")) {
                return List.of(series(Map.of("uri", "/chat/questions"), 100.0));
            }
            if (query.contains("histogram_quantile(0.95")) {
                return List.of(series(Map.of("uri", "/chat/questions"), 200.0));
            }
            if (query.contains("histogram_quantile(0.99")) {
                return List.of(series(Map.of("uri", "/chat/questions"), 300.0));
            }
            return List.of(series(Map.of("uri", "/chat/questions"), 120.0));
        });

        var result = service.getEndpoints(ServiceMonitoringRange.ONE_HOUR);

        assertThat(result.window().range()).isEqualTo("1h");
        assertThat(result.items()).singleElement().satisfies(item -> {
            assertThat(item.uri()).isEqualTo("/chat/questions");
            assertThat(item.requestCount()).isEqualTo(120.0);
            assertThat(item.rps()).isEqualTo(120.0 / 3600);
            assertThat(item.client4xxRate()).isEqualTo(0.05);
            assertThat(item.server5xxRate()).isEqualTo(0.025);
            assertThat(item.p50Millis()).isEqualTo(100.0);
            assertThat(item.p95Millis()).isEqualTo(200.0);
            assertThat(item.p99Millis()).isEqualTo(300.0);
        });
    }

    private PrometheusSeries series(Map<String, String> labels, double value) {
        return new PrometheusSeries(labels, List.of(new PrometheusPoint(AT, value)));
    }
}
