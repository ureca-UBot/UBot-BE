package com.ubot.monitoring.operationmonitoring.dto;

import java.time.Instant;
import java.util.List;

public record EndpointMonitoringResponse(
        Window window,
        List<Item> items
) {
    public record Window(String range, Instant from, Instant to) {
    }

    public record Item(
            String uri,
            double rps,
            double requestCount,
            double client4xxRate,
            double server5xxRate,
            Double p50Millis,
            Double p95Millis,
            Double p99Millis
    ) {
    }
}
