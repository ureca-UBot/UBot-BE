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

    private ServiceMonitoringOverviewResponse queryOverview(ServiceMonitoringRange range) {
        Instant end = Instant.now();
        Instant start = end.minus(range.duration());

        ServiceMonitoringOverviewResponse.ServiceStatuses statuses = new ServiceMonitoringOverviewResponse.ServiceStatuses(
                toStatus(queryNow("up{job=\"ubot-backend\"}", end)),
                toStatus(queryNow("max(pg_up)", end)),
                toStatus(queryNow("max(redis_up)", end)),
                toStatus(queryNow("max(chat_worker_ready * on(job, instance) up)", end))
        );

        ServiceMonitoringOverviewResponse.DatabasePool currentPool = new ServiceMonitoringOverviewResponse.DatabasePool(
                latestValue(queryNow("sum(hikaricp_connections_active)", end)),
                latestValue(queryNow("sum(hikaricp_connections_idle)", end)),
                latestValue(queryNow("sum(hikaricp_connections_pending)", end)),
                latestValue(queryNow("sum(hikaricp_connections_max)", end))
        );
        ServiceMonitoringOverviewResponse.Summary summary = new ServiceMonitoringOverviewResponse.Summary(
                latestValue(queryNow("sum(rate(" + API_REQUESTS + "[1m]))", end)),
                latestValue(queryNow(errorRateQuery("5..", "5m"), end)),
                latestValue(queryNow(latencyQuery(0.95, "5m"), end)),
                latestValue(queryNow(heapUsageQuery(), end)),
                currentPool
        );

        ServiceMonitoringOverviewResponse.Series series = new ServiceMonitoringOverviewResponse.Series(
                queryRange("sum(rate(" + API_REQUESTS + "[1m]))", start, end, range.step()),
                new ServiceMonitoringOverviewResponse.ErrorRate(
                        queryRange(errorRateQuery("4..", "5m"), start, end, range.step()),
                        queryRange(errorRateQuery("5..", "5m"), start, end, range.step())
                ),
                new ServiceMonitoringOverviewResponse.ApiLatency(
                        queryRange(latencyQuery(0.50, "5m"), start, end, range.step()),
                        queryRange(latencyQuery(0.95, "5m"), start, end, range.step()),
                        queryRange(latencyQuery(0.99, "5m"), start, end, range.step())
                ),
                new ServiceMonitoringOverviewResponse.Backend(
                        queryRange("process_cpu_usage{job=\"ubot-backend\"}", start, end, range.step()),
                        queryRange(heapUsageQuery(), start, end, range.step()),
                        queryRange("sum(rate(jvm_gc_pause_seconds_sum{job=\"ubot-backend\"}[5m]))", start, end, range.step()),
                        queryRange("jvm_threads_live_threads{job=\"ubot-backend\"}", start, end, range.step())
                ),
                new ServiceMonitoringOverviewResponse.DatabasePoolSeries(
                        queryRange("sum(hikaricp_connections_active)", start, end, range.step()),
                        queryRange("sum(hikaricp_connections_idle)", start, end, range.step()),
                        queryRange("sum(hikaricp_connections_pending)", start, end, range.step())
                ),
                new ServiceMonitoringOverviewResponse.ContainerResources(
                        queryRangeByLabel(
                                "100 * sum(rate(container_cpu_usage_seconds_total"
                                        + "{container_label_com_docker_compose_service=~\"backend|postgres|redis\"}[5m]))"
                                        + " by (container_label_com_docker_compose_service)",
                                "container_label_com_docker_compose_service", start, end, range.step()),
                        queryRangeByLabel(
                                "sum(container_memory_working_set_bytes"
                                        + "{container_label_com_docker_compose_service=~\"backend|postgres|redis\"})"
                                        + " by (container_label_com_docker_compose_service)",
                                "container_label_com_docker_compose_service", start, end, range.step())
                )
        );

        return new ServiceMonitoringOverviewResponse(
                new ServiceMonitoringOverviewResponse.Window(range.value(), start, end, range.step().toSeconds()),
                statuses, summary, series,
                new ServiceMonitoringOverviewResponse.Worker(
                        latestValue(queryNow("max(chat_jobs_queued)", end)),
                        latestValue(queryNow("max(chat_jobs_oldest_queued_seconds)", end)),
                        latestValue(queryNow("sum(chat_worker_active)", end)),
                        latestValue(queryNow("sum(rate(chat_worker_completed_total{status=\"failed\"}[5m]))", end)),
                        latestValue(queryNow("sum(rate(chat_worker_execution_seconds_sum[5m])) / sum(rate(chat_worker_execution_seconds_count[5m]))", end)))
        );
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
        Map<String, Double> values = new LinkedHashMap<>();
        for (PrometheusSeries item : series) {
            String uri = item.labels().get("uri");
            Double value = latestValue(List.of(item));
            if (uri != null && value != null) {
                values.put(uri, value);
            }
        }
        return values;
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
