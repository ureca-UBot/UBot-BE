package com.ubot.monitoring.operationmonitoring.service;

import com.ubot.monitoring.operationmonitoring.PrometheusQueryClient;
import com.ubot.monitoring.operationmonitoring.PrometheusQueryClient.PrometheusPoint;
import com.ubot.monitoring.operationmonitoring.PrometheusQueryClient.PrometheusSeries;
import com.ubot.monitoring.operationmonitoring.ServiceMonitoringRange;
import com.ubot.monitoring.operationmonitoring.dto.EndpointMonitoringResponse;
import com.ubot.monitoring.operationmonitoring.dto.MetricPoint;
import com.ubot.monitoring.operationmonitoring.dto.ServiceMonitoringOverviewResponse;
import com.ubot.monitoring.operationmonitoring.dto.ServiceMonitoringOverviewResponse.ServiceStatus;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class ServiceOperationMonitoringService {

    private static final String API_REQUESTS = "http_server_requests_seconds_count{uri!~\"/actuator.*|UNKNOWN\"}";
    private static final String API_BUCKETS = "http_server_requests_seconds_bucket{uri!~\"/actuator.*|UNKNOWN\"}";

    private final PrometheusQueryClient prometheusQueryClient;

    public ServiceMonitoringOverviewResponse getOverview(ServiceMonitoringRange range) {
        return queryOverview(range);
    }

    public EndpointMonitoringResponse getEndpoints(ServiceMonitoringRange range) {
        return queryEndpoints(range);
    }

    // 질의가 40개가 넘어 순서대로 보내면 화면이 느려지므로 한 번에 보내고 모두 끝난 뒤 조합함
    private ServiceMonitoringOverviewResponse queryOverview(ServiceMonitoringRange range) {
        Instant end = Instant.now();
        Instant start = end.minus(range.duration());
        String window = range.value();

        try (Queries q = new Queries(start, end, range.step())) {
            var backendUp = q.now("up{job=\"ubot-backend\"}");
            var postgresUp = q.now("max(pg_up)");
            var redisUp = q.now("max(redis_up)");
            var workerUp = q.now("max(chat_worker_ready * on(job, instance) up)");

            var poolActive = q.latest("sum(hikaricp_connections_active)");
            var poolIdle = q.latest("sum(hikaricp_connections_idle)");
            var poolPending = q.latest("sum(hikaricp_connections_pending)");
            var poolMax = q.latest("sum(hikaricp_connections_max)");
            var apiRps = q.latest("sum(rate(" + API_REQUESTS + "[1m]))");
            var api5xx = q.latest(errorRateQuery("5..", "5m"));
            var apiP95 = q.latest(latencyQuery(0.95, "5m"));
            var heap = q.latest(heapUsageQuery());

            var rpsSeries = q.range("sum(rate(" + API_REQUESTS + "[1m]))");
            var errors4xx = q.range(errorRateQuery("4..", "5m"));
            var errors5xx = q.range(errorRateQuery("5..", "5m"));
            var latencyP50 = q.range(latencyQuery(0.50, "5m"));
            var latencyP95 = q.range(latencyQuery(0.95, "5m"));
            var latencyP99 = q.range(latencyQuery(0.99, "5m"));
            var cpu = q.range("process_cpu_usage{job=\"ubot-backend\"}");
            var heapSeries = q.range(heapUsageQuery());
            var gcPause = q.range("sum(rate(jvm_gc_pause_seconds_sum{job=\"ubot-backend\"}[5m]))");
            var threads = q.range("jvm_threads_live_threads{job=\"ubot-backend\"}");
            var poolActiveSeries = q.range("sum(hikaricp_connections_active)");
            var poolIdleSeries = q.range("sum(hikaricp_connections_idle)");
            var poolPendingSeries = q.range("sum(hikaricp_connections_pending)");
            var containerCpu = q.rangeByLabel(
                    "100 * sum(rate(container_cpu_usage_seconds_total"
                            + "{container_label_com_docker_compose_service=~\"backend|postgres|redis\"}[5m]))"
                            + " by (container_label_com_docker_compose_service)",
                    "container_label_com_docker_compose_service");
            var containerMemory = q.rangeByLabel(
                    "sum(container_memory_working_set_bytes"
                            + "{container_label_com_docker_compose_service=~\"backend|postgres|redis\"})"
                            + " by (container_label_com_docker_compose_service)",
                    "container_label_com_docker_compose_service");

            var worker = workerQueries(q, window);
            var llm = llmQueries(q, window);
            var retrieval = retrievalQueries(q, window);
            var embedding = embeddingQueries(q, window);
            var database = databaseQueries(q, window);
            var redis = redisQueries(q);

            ServiceMonitoringOverviewResponse.ServiceStatuses statuses = new ServiceMonitoringOverviewResponse.ServiceStatuses(
                    toStatus(join(backendUp)), toStatus(join(postgresUp)), toStatus(join(redisUp)), toStatus(join(workerUp)));
            ServiceMonitoringOverviewResponse.Summary summary = new ServiceMonitoringOverviewResponse.Summary(
                    join(apiRps), join(api5xx), join(apiP95), join(heap),
                    new ServiceMonitoringOverviewResponse.DatabasePool(
                            join(poolActive), join(poolIdle), join(poolPending), join(poolMax)));
            ServiceMonitoringOverviewResponse.Series series = new ServiceMonitoringOverviewResponse.Series(
                    join(rpsSeries),
                    new ServiceMonitoringOverviewResponse.ErrorRate(join(errors4xx), join(errors5xx)),
                    new ServiceMonitoringOverviewResponse.ApiLatency(join(latencyP50), join(latencyP95), join(latencyP99)),
                    new ServiceMonitoringOverviewResponse.Backend(join(cpu), join(heapSeries), join(gcPause), join(threads)),
                    new ServiceMonitoringOverviewResponse.DatabasePoolSeries(
                            join(poolActiveSeries), join(poolIdleSeries), join(poolPendingSeries)),
                    new ServiceMonitoringOverviewResponse.ContainerResources(join(containerCpu), join(containerMemory)));

            return new ServiceMonitoringOverviewResponse(
                    new ServiceMonitoringOverviewResponse.Window(window, start, end, range.step().toSeconds()),
                    statuses, summary, series,
                    join(worker), join(llm), join(retrieval), join(embedding), join(database), join(redis));
        }
    }

    private CompletableFuture<ServiceMonitoringOverviewResponse.Worker> workerQueries(Queries q, String window) {
        String completed = "sum(increase(chat_worker_completed_total[" + window + "]))";
        // 대기 만료·전달 횟수 소진·메시지 없는 정리도 사용자에게는 실패로 끝난 작업임
        String otherTerminal = " + (sum(increase(chat_worker_queue_expired_total[" + window + "])) or vector(0))"
                + " + (sum(increase(chat_worker_terminated_total[" + window + "])) or vector(0))";
        var queued = q.latest("max(chat_jobs_queued)");
        var oldest = q.latest("max(chat_jobs_oldest_queued_seconds)");
        var active = q.latest("sum(chat_worker_active)");
        var failuresPerSecond = q.latest("sum(rate(chat_worker_completed_total{status=\"failed\"}[5m]))");
        var meanExecution = q.latest(
                "sum(rate(chat_worker_execution_seconds_sum[5m])) / sum(rate(chat_worker_execution_seconds_count[5m]))");
        var p95Execution = q.latest(histogramQuantile(0.95, "chat_worker_execution_seconds_bucket", "", window));
        var p95QueueWait = q.latest(histogramQuantile(0.95, "chat_jobs_queue_wait_seconds_bucket", "", window));
        var retryRatio = q.latest(ratio("chat_jobs_submitted_total{type=\"retry\"}",
                "sum(increase(chat_jobs_submitted_total{type=\"question\"}[" + window + "]))", window));
        var terminalFailureRatio = q.latest("((sum(increase(chat_worker_completed_total"
                + "{status=\"failed\",code!~\"CHAT-012|CHAT-013\"}[" + window + "])) or vector(0))" + otherTerminal + ")"
                + " / ((" + completed + " or vector(0))" + otherTerminal + ")");
        var failureRatioByCode = q.latestByLabel("sum(increase(chat_worker_completed_total{status=\"failed\"}["
                + window + "])) by (code) / scalar(" + completed + ")", "code");
        var reclaims = q.latestByLabel("sum(increase(chat_worker_reclaimed_total[" + window + "])) by (reason)", "reason");
        var leaseLosses = q.latest(count("chat_worker_lease_lost_total", window));
        var heartbeatFailures = q.latest(count("chat_worker_heartbeat_failures_total", window));
        return CompletableFuture.allOf(queued, oldest, active, failuresPerSecond, meanExecution, p95Execution,
                        p95QueueWait, retryRatio, terminalFailureRatio, failureRatioByCode, reclaims, leaseLosses,
                        heartbeatFailures)
                .thenApply(ignored -> new ServiceMonitoringOverviewResponse.Worker(
                        queued.join(), oldest.join(), active.join(), failuresPerSecond.join(), meanExecution.join(),
                        p95Execution.join(), p95QueueWait.join(), retryRatio.join(), terminalFailureRatio.join(),
                        failureRatioByCode.join(), reclaims.join(), leaseLosses.join(), heartbeatFailures.join()));
    }

    private CompletableFuture<ServiceMonitoringOverviewResponse.Llm> llmQueries(Queries q, String window) {
        String requests = "sum(increase(llm_requests_seconds_count[" + window + "]))";
        String rejected = "ai_output_guard_total{stage=\"body\",outcome=\"rejected\"}";
        var p95Answer = q.latest(histogramQuantile(0.95, "llm_requests_seconds_bucket", "kind=\"answer\"", window));
        var p95Judgment = q.latest(histogramQuantile(0.95, "llm_requests_seconds_bucket", "kind=\"judgment\"", window));
        var timeoutRatio = q.latest(ratio("llm_requests_seconds_count{outcome=\"timeout\"}", requests, window));
        var errorRatio = q.latest(ratio("llm_requests_seconds_count{outcome!~\"success|cancelled\"}", requests, window));
        // api 역할의 LlmService(관리자 기능용)는 동시 호출 상한이 따로 있어 Worker 포화도에서 뺌
        var inUse = q.latest("sum(llm_concurrency_in_use{role!=\"api\"})");
        var limit = q.latest("sum(llm_concurrency_limit{role!=\"api\"})");
        var waiting = q.latest("sum(llm_concurrency_waiting{role!=\"api\"})");
        var p95PermitWait = q.latest(histogramQuantile(0.95, "llm_permit_wait_seconds_bucket", "", window));
        var rejectionRatio = q.latest(ratio(rejected,
                "sum(increase(ai_output_guard_total{stage=\"body\"}[" + window + "]))", window));
        var rejectionsByRule = q.latestByLabel("sum(increase(" + rejected + "[" + window + "])) by (rule)", "rule");
        return CompletableFuture.allOf(p95Answer, p95Judgment, timeoutRatio, errorRatio, inUse, limit, waiting,
                        p95PermitWait, rejectionRatio, rejectionsByRule)
                .thenApply(ignored -> new ServiceMonitoringOverviewResponse.Llm(
                        p95Answer.join(), p95Judgment.join(), timeoutRatio.join(), errorRatio.join(),
                        inUse.join(), limit.join(), waiting.join(), p95PermitWait.join(),
                        rejectionRatio.join(), rejectionsByRule.join()));
    }

    private CompletableFuture<ServiceMonitoringOverviewResponse.Retrieval> retrievalQueries(Queries q, String window) {
        String completed = "sum(increase(chat_worker_completed_total[" + window + "]))";
        var embeddingP95 = q.latest(histogramQuantile(0.95, "embedding_requests_seconds_bucket", "", window));
        var embeddingErrors = q.latest(ratio("embedding_requests_seconds_count{outcome!=\"success\"}",
                "sum(increase(embedding_requests_seconds_count[" + window + "]))", window));
        var vectorSearchP95 = q.latest(histogramQuantile(0.95, "faq_vector_search_seconds_bucket", "", window));
        var noFaq = q.latest(ratio("chat_worker_completed_total{code=\"CHAT-012\"}", completed, window));
        var insufficient = q.latest(ratio("chat_worker_completed_total{code=\"CHAT-013\"}", completed, window));
        var similarityP10 = q.latest(histogramQuantile(0.10, "faq_retrieval_top_similarity_bucket", "", window));
        var similarityP50 = q.latest(histogramQuantile(0.50, "faq_retrieval_top_similarity_bucket", "", window));
        var similarityBuckets = q.latestByLabel(
                "sum(increase(faq_retrieval_top_similarity_bucket[" + window + "])) by (le)", "le");
        return CompletableFuture.allOf(embeddingP95, embeddingErrors, vectorSearchP95, noFaq, insufficient,
                        similarityP10, similarityP50, similarityBuckets)
                .thenApply(ignored -> new ServiceMonitoringOverviewResponse.Retrieval(
                        embeddingP95.join(), embeddingErrors.join(), vectorSearchP95.join(), noFaq.join(),
                        insufficient.join(), similarityP10.join(), similarityP50.join(), similarityBuckets.join()));
    }

    private CompletableFuture<ServiceMonitoringOverviewResponse.EmbeddingLifecycle> embeddingQueries(
            Queries q, String window) {
        var missing = q.latest("max(faq_embeddings_missing)");
        var stale = q.latest("max(faq_embeddings_stale)");
        var pending = q.latest("max(outbox_events_pending)");
        var retrying = q.latest("max(outbox_events_retrying)");
        var dead = q.latest("max(outbox_events_dead)");
        var counselFailures = q.latest(count("counsel_embedding_jobs_total{status=\"failed\"}", window));
        return CompletableFuture.allOf(missing, stale, pending, retrying, dead, counselFailures)
                .thenApply(ignored -> new ServiceMonitoringOverviewResponse.EmbeddingLifecycle(
                        missing.join(), stale.join(), pending.join(), retrying.join(), dead.join(),
                        counselFailures.join()));
    }

    private CompletableFuture<ServiceMonitoringOverviewResponse.DatabaseLatency> databaseQueries(
            Queries q, String window) {
        var meanAcquire = q.latest("sum(rate(hikaricp_connections_acquire_seconds_sum[" + window + "]))"
                + " / sum(rate(hikaricp_connections_acquire_seconds_count[" + window + "])) * 1000");
        var maxAcquire = q.latest("max(hikaricp_connections_acquire_seconds_max) * 1000");
        var p95Usage = q.latest(histogramQuantile(0.95, "hikaricp_connections_usage_seconds_bucket", "", window) + " * 1000");
        var longestTransaction = q.latest("max(pg_stat_activity_max_tx_duration)");
        return CompletableFuture.allOf(meanAcquire, maxAcquire, p95Usage, longestTransaction)
                .thenApply(ignored -> new ServiceMonitoringOverviewResponse.DatabaseLatency(
                        meanAcquire.join(), maxAcquire.join(), p95Usage.join(), longestTransaction.join()));
    }

    private CompletableFuture<ServiceMonitoringOverviewResponse.RedisStatus> redisQueries(Queries q) {
        var used = q.latest("max(redis_memory_used_bytes)");
        var max = q.latest("max(redis_memory_max_bytes)");
        var length = q.latest("max(redis_stream_length{stream=\"chat:jobs\"})");
        var pending = q.latest("max(redis_stream_group_messages_pending{stream=\"chat:jobs\"})");
        var lag = q.latest("max(redis_stream_group_lag{stream=\"chat:jobs\"})");
        return CompletableFuture.allOf(used, max, length, pending, lag)
                .thenApply(ignored -> new ServiceMonitoringOverviewResponse.RedisStatus(
                        used.join(), max.join(), length.join(), pending.join(), lag.join()));
    }

    // selector가 없으면 0으로 보고(아직 한 번도 안 생긴 counter) 분모로 나눔
    private String ratio(String selector, String denominator, String window) {
        return "(sum(increase(" + selector + "[" + window + "])) or vector(0)) / " + denominator;
    }

    // 아직 한 번도 안 생긴 counter는 0
    private String count(String selector, String window) {
        return "sum(increase(" + selector + "[" + window + "])) or vector(0)";
    }

    private String histogramQuantile(double quantile, String bucket, String filter, String window) {
        return "histogram_quantile(" + quantile + ", sum(rate(" + bucket + "{" + filter + "}[" + window + "])) by (le))";
    }

    private EndpointMonitoringResponse queryEndpoints(ServiceMonitoringRange range) {
        Instant end = Instant.now();
        Instant start = end.minus(range.duration());
        String duration = range.value();
        Map<String, Double> requestCounts = valuesByUri(queryNow(
                "sum(increase(" + API_REQUESTS + "[" + duration + "])) by (uri)", end));
        Map<String, Double> clientErrors = valuesByUri(queryNow(
                "sum(increase(http_server_requests_seconds_count{uri!~\"/actuator.*|UNKNOWN\",status=~\"4..\"}["
                        + duration + "])) by (uri)", end));
        Map<String, Double> serverErrors = valuesByUri(queryNow(
                "sum(increase(http_server_requests_seconds_count{uri!~\"/actuator.*|UNKNOWN\",status=~\"5..\"}["
                        + duration + "])) by (uri)", end));
        Map<String, Double> p50 = valuesByUri(queryNow(endpointLatencyQuery(0.50, duration), end));
        Map<String, Double> p95 = valuesByUri(queryNow(endpointLatencyQuery(0.95, duration), end));
        Map<String, Double> p99 = valuesByUri(queryNow(endpointLatencyQuery(0.99, duration), end));

        List<EndpointMonitoringResponse.Item> items = requestCounts.entrySet().stream()
                .map(entry -> toEndpointItem(entry.getKey(), entry.getValue(), range.duration(), clientErrors, serverErrors, p50, p95, p99))
                .sorted(Comparator.comparingDouble(EndpointMonitoringResponse.Item::requestCount).reversed())
                .toList();
        return new EndpointMonitoringResponse(new EndpointMonitoringResponse.Window(range.value(), start, end), items);
    }

    private EndpointMonitoringResponse.Item toEndpointItem(
            String uri,
            double requestCount,
            Duration duration,
            Map<String, Double> clientErrors,
            Map<String, Double> serverErrors,
            Map<String, Double> p50,
            Map<String, Double> p95,
            Map<String, Double> p99
    ) {
        return new EndpointMonitoringResponse.Item(
                uri,
                requestCount / duration.toSeconds(),
                requestCount,
                ratio(clientErrors.get(uri), requestCount),
                ratio(serverErrors.get(uri), requestCount),
                p50.get(uri), p95.get(uri), p99.get(uri)
        );
    }

    private double ratio(Double numerator, double denominator) {
        return denominator == 0 ? 0 : (numerator == null ? 0 : numerator) / denominator;
    }

    private String errorRateQuery(String status, String window) {
        return "(sum(rate(http_server_requests_seconds_count{uri!~\"/actuator.*|UNKNOWN\",status=~\""
                + status + "\"}[" + window + "])) or vector(0))"
                + " / sum(rate(" + API_REQUESTS + "[" + window + "]))";
    }

    private String latencyQuery(double quantile, String window) {
        return "histogram_quantile(" + quantile + ", sum(rate(" + API_BUCKETS + "[" + window + "])) by (le)) * 1000";
    }

    private String endpointLatencyQuery(double quantile, String window) {
        return "histogram_quantile(" + quantile + ", sum(increase(" + API_BUCKETS + "[" + window + "])) by (uri, le)) * 1000";
    }

    private String heapUsageQuery() {
        return "sum(jvm_memory_used_bytes{job=\"ubot-backend\",area=\"heap\"}) / sum(jvm_memory_max_bytes{job=\"ubot-backend\",area=\"heap\"})";
    }

    private List<PrometheusSeries> queryNow(String query, Instant end) {
        return prometheusQueryClient.query(query, end);
    }

    private List<MetricPoint> queryRange(String query, Instant start, Instant end, Duration step) {
        return prometheusQueryClient.queryRange(query, start, end, step).stream()
                .flatMap(series -> series.points().stream())
                .map(point -> new MetricPoint(point.at(), point.value()))
                .toList();
    }

    private Map<String, List<MetricPoint>> queryRangeByLabel(
            String query, String label, Instant start, Instant end, Duration step
    ) {
        Map<String, List<MetricPoint>> result = new LinkedHashMap<>();
        for (PrometheusSeries series : prometheusQueryClient.queryRange(query, start, end, step)) {
            String key = series.labels().get(label);
            if (key != null) {
                result.put(key, toMetricPoints(series.points()));
            }
        }
        return result;
    }

    private Map<String, Double> valuesByUri(List<PrometheusSeries> series) {
        return valuesByLabel(series, "uri");
    }

    private Map<String, Double> valuesByLabel(List<PrometheusSeries> series, String label) {
        Map<String, Double> values = new LinkedHashMap<>();
        for (PrometheusSeries item : series) {
            String key = item.labels().get(label);
            Double value = latestValue(List.of(item));
            if (key != null && value != null) {
                values.put(key, value);
            }
        }
        return values;
    }

    // 질의 하나라도 실패하면(Prometheus 장애) 순서대로 보낼 때와 같은 예외로 응답함
    private static <T> T join(CompletableFuture<T> future) {
        try {
            return future.join();
        } catch (CompletionException exception) {
            if (exception.getCause() instanceof RuntimeException cause) {
                throw cause;
            }
            throw exception;
        }
    }

    // 개요 한 번에 쓰는 질의를 가상 스레드로 동시에 보냄. 닫을 때 남은 질의가 끝나기를 기다림
    private final class Queries implements AutoCloseable {
        private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
        private final Instant start;
        private final Instant end;
        private final Duration step;

        private Queries(Instant start, Instant end, Duration step) {
            this.start = start;
            this.end = end;
            this.step = step;
        }

        private CompletableFuture<List<PrometheusSeries>> now(String query) {
            return CompletableFuture.supplyAsync(() -> queryNow(query, end), executor);
        }

        private CompletableFuture<Double> latest(String query) {
            return now(query).thenApply(ServiceOperationMonitoringService.this::latestValue);
        }

        private CompletableFuture<Map<String, Double>> latestByLabel(String query, String label) {
            return now(query).thenApply(series -> valuesByLabel(series, label));
        }

        private CompletableFuture<List<MetricPoint>> range(String query) {
            return CompletableFuture.supplyAsync(() -> queryRange(query, start, end, step), executor);
        }

        private CompletableFuture<Map<String, List<MetricPoint>>> rangeByLabel(String query, String label) {
            return CompletableFuture.supplyAsync(() -> queryRangeByLabel(query, label, start, end, step), executor);
        }

        @Override
        public void close() {
            executor.close();
        }
    }

    private ServiceStatus toStatus(List<PrometheusSeries> series) {
        Double value = latestValue(series);
        if (value == null) {
            return ServiceStatus.UNKNOWN;
        }
        return value == 1 ? ServiceStatus.UP : ServiceStatus.DOWN;
    }

    private Double latestValue(List<PrometheusSeries> series) {
        return series.stream().flatMap(item -> item.points().stream())
                .max(Comparator.comparing(PrometheusPoint::at))
                .map(PrometheusPoint::value)
                .orElse(null);
    }

    private List<MetricPoint> toMetricPoints(List<PrometheusPoint> points) {
        return points.stream().map(point -> new MetricPoint(point.at(), point.value())).toList();
    }

}
