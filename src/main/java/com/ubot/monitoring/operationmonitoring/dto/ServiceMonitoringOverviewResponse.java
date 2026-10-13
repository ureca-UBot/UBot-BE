package com.ubot.monitoring.operationmonitoring.dto;

import java.time.Instant;
import java.util.List;
import java.util.Map;

public record ServiceMonitoringOverviewResponse(
        Window window,
        ServiceStatuses services,
        Summary summary,
        Series series
) {
    public record Window(String range, Instant from, Instant to, long sampleIntervalSeconds) {
    }

    public record ServiceStatuses(ServiceStatus backend, ServiceStatus postgres, ServiceStatus redis) {
    }

    public enum ServiceStatus {
        UP, DOWN, UNKNOWN
    }

    public record Summary(
            Double apiRps,
            Double api5xxRate,
            Double apiP95Millis,
            Double jvmHeapUsageRate,
            DatabasePool databasePool
    ) {
    }

    public record DatabasePool(Double active, Double idle, Double pending, Double max) {
    }

    public record Series(
            List<MetricPoint> apiRps,
            ErrorRate errorRate,
            ApiLatency apiLatencyMillis,
            Backend backend,
            DatabasePoolSeries databasePool,
            ContainerResources containers
    ) {
    }

    public record ErrorRate(List<MetricPoint> client4xx, List<MetricPoint> server5xx) {
    }

    public record ApiLatency(List<MetricPoint> p50, List<MetricPoint> p95, List<MetricPoint> p99) {
    }

    public record Backend(
            List<MetricPoint> cpuUsageRate,
            List<MetricPoint> jvmHeapUsageRate,
            List<MetricPoint> gcPauseSecondsPerSecond,
            List<MetricPoint> liveThreads
    ) {
    }

    public record DatabasePoolSeries(
            List<MetricPoint> active,
            List<MetricPoint> idle,
            List<MetricPoint> pending
    ) {
    }

    public record ContainerResources(
            Map<String, List<MetricPoint>> cpuUsageRate,
            Map<String, List<MetricPoint>> memoryBytes
    ) {
    }
}
