package com.ubot.monitoring.operationmonitoring.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.ubot.monitoring.operationmonitoring.PrometheusQueryClient;
import com.ubot.monitoring.operationmonitoring.PrometheusQueryClient.PrometheusPoint;
import com.ubot.monitoring.operationmonitoring.PrometheusQueryClient.PrometheusSeries;
import com.ubot.monitoring.operationmonitoring.ServiceMonitoringRange;
import com.ubot.monitoring.operationmonitoring.exception.OperationMonitoringErrorCode;
import com.ubot.monitoring.operationmonitoring.exception.OperationMonitoringException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
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
                .contains("sum(rate(jvm_gc_pause_seconds_sum{job=\"ubot-backend\"}[5m]))");
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

    @Test
    @DisplayName("작업·LLM·검색·임베딩·DB·Redis 지표를 조회 기간 기준 질의로 채우고, 데이터가 없으면 null로 둔다")
    void assemblesOperationalSections() {
        when(prometheusQueryClient.query(any(), any())).thenAnswer(invocation -> {
            String query = invocation.getArgument(0);
            if (query.contains("chat_jobs_submitted_total{type=\"retry\"}[1h]")) return List.of(series(Map.of(), 0.25));
            if (query.contains("by (code)")) {
                return List.of(series(Map.of("code", "CHAT-012"), 0.1), series(Map.of("code", "CHAT-016"), 0.05));
            }
            if (query.contains("by (reason)")) return List.of(series(Map.of("reason", "heartbeat"), 2.0));
            if (query.contains("llm_requests_seconds_bucket{client=\"chat\",kind=\"answer\"}[1h]")) return List.of(series(Map.of(), 4.2));
            if (query.contains("llm_concurrency_limit{client=\"chat\",role!=\"api\"}")) return List.of(series(Map.of(), 6.0));
            if (query.contains("faq_retrieval_top_similarity_bucket[1h])) by (le)")) {
                return List.of(series(Map.of("le", "0.6"), 3.0), series(Map.of("le", "+Inf"), 10.0));
            }
            if (query.equals("max(faq_embeddings_missing)")) return List.of(series(Map.of(), 7.0));
            if (query.contains("redis_stream_group_messages_pending{stream=\"chat:jobs\"}")) return List.of(series(Map.of(), 3.0));
            if (query.equals("max(pg_stat_activity_max_tx_duration)")) return List.of(series(Map.of(), 12.0));
            return List.of();
        });
        when(prometheusQueryClient.queryRange(any(), any(), any(), any())).thenReturn(List.of());

        var overview = service.getOverview(ServiceMonitoringRange.ONE_HOUR);

        assertThat(overview.worker().retryRatio()).isEqualTo(0.25);
        assertThat(overview.worker().failureRatioByCode()).containsEntry("CHAT-012", 0.1).containsEntry("CHAT-016", 0.05);
        assertThat(overview.worker().reclaimsByReason()).containsEntry("heartbeat", 2.0);
        assertThat(overview.llm().p95AnswerSeconds()).isEqualTo(4.2);
        assertThat(overview.llm().concurrencyLimit()).isEqualTo(6.0);
        assertThat(overview.llm().timeoutRatio()).as("데이터가 없으면 null").isNull();
        assertThat(overview.retrieval().topSimilarityBuckets()).containsEntry("0.6", 3.0).containsEntry("+Inf", 10.0);
        assertThat(overview.embedding().faqMissing()).isEqualTo(7.0);
        assertThat(overview.database().longestTransactionSeconds()).isEqualTo(12.0);
        assertThat(overview.redis().pendingMessages()).isEqualTo(3.0);
    }

    @Test
    @DisplayName("질의를 동시에 보내도 Prometheus 장애는 순서대로 보낼 때와 같은 예외로 알린다")
    void propagatesPrometheusFailureFromParallelQueries() {
        when(prometheusQueryClient.query(any(), any())).thenAnswer(invocation -> {
            if (invocation.<String>getArgument(0).contains("llm_concurrency_limit")) {
                throw new OperationMonitoringException(OperationMonitoringErrorCode.PROMETHEUS_UNAVAILABLE);
            }
            return List.of();
        });
        when(prometheusQueryClient.queryRange(any(), any(), any(), any())).thenReturn(List.of());

        assertThatThrownBy(() -> service.getOverview(ServiceMonitoringRange.ONE_HOUR))
                .isInstanceOf(OperationMonitoringException.class);
    }

    @Test
    void workerFailureAndQueueAreVisibleWhileBackendRemainsUp() {
        when(prometheusQueryClient.query(any(), any())).thenAnswer(invocation -> {
            String query = invocation.getArgument(0);
            if (query.contains("chat_worker_ready")) return List.of(series(Map.of(), 0.0));
            if (query.equals("max(chat_jobs_queued)")) return List.of(series(Map.of(), 17.0));
            if (query.contains("chat_worker_completed_total")) return List.of(series(Map.of(), 2.0));
            return List.of(series(Map.of(), 1.0));
        });
        var overview = service.getOverview(ServiceMonitoringRange.ONE_HOUR);
        assertThat(overview.services().backend()).isEqualTo(
                com.ubot.monitoring.operationmonitoring.dto.ServiceMonitoringOverviewResponse.ServiceStatus.UP);
        assertThat(overview.services().worker()).isEqualTo(
                com.ubot.monitoring.operationmonitoring.dto.ServiceMonitoringOverviewResponse.ServiceStatus.DOWN);
        assertThat(overview.worker().queued()).isEqualTo(17.0);
        assertThat(overview.worker().failuresPerSecond()).isEqualTo(2.0);
    }

    @ParameterizedTest
    @EnumSource(ServiceMonitoringRange.class)
    void existingLlmOverviewExcludesGuardCallsFromEverySelectorAndDenominator(ServiceMonitoringRange range) {
        when(prometheusQueryClient.query(any(), any())).thenAnswer(invocation -> {
            String query = invocation.getArgument(0);
            if (!query.contains("llm_")) return List.of();
            var selectors = Pattern.compile("llm_[a-z_]+(?:\\{([^}]*)})?").matcher(query);
            while (selectors.find()) {
                assertThat(selectors.group(1)).as("기존 채팅 지표 selector: %s", selectors.group())
                        .isNotNull().contains("client=\"chat\"");
            }
            if (query.contains("outcome=\"timeout\"")) return List.of(series(Map.of(), 0.02));
            if (query.contains("outcome!~")) return List.of(series(Map.of(), 0.04));
            if (query.contains("kind=\"judgment\"")) return List.of(series(Map.of(), 1.2));
            return List.of(series(Map.of(), 4.0));
        });
        when(prometheusQueryClient.queryRange(any(), any(), any(), any())).thenReturn(List.of());

        var llm = service.getOverview(range).llm();

        assertThat(llm.p95JudgmentSeconds()).isEqualTo(1.2);
        assertThat(llm.timeoutRatio()).isEqualTo(0.02);
        assertThat(llm.errorRatio()).isEqualTo(0.04);
        assertThat(llm.concurrencyLimit()).isEqualTo(4.0);
    }

    @Test
    void guardOnlyDataDoesNotAppearAsChatLatencyOrCapacity() {
        when(prometheusQueryClient.query(any(), any())).thenAnswer(invocation -> {
            String query = invocation.getArgument(0);
            // 채팅 series가 없고 guard series만 있는 경우를 모의합니다.
            if (query.contains("llm_") && !query.contains("client=\"chat\"")) return List.of(series(Map.of(), 99.0));
            return List.of();
        });
        when(prometheusQueryClient.queryRange(any(), any(), any(), any())).thenReturn(List.of());

        var llm = service.getOverview(ServiceMonitoringRange.ONE_HOUR).llm();

        assertThat(llm.p95JudgmentSeconds()).isNull();
        assertThat(llm.timeoutRatio()).isNull();
        assertThat(llm.errorRatio()).isNull();
        assertThat(llm.concurrencyLimit()).isNull();
        assertThat(llm.p95PermitWaitSeconds()).isNull();
    }
}
