package com.ubot.monitoring.operationmonitoring.dto;

import java.time.Instant;

public record MetricPoint(Instant at, double value) {
}
