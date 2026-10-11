package com.ubot.monitoring.dto.model.detail;

import java.time.LocalDateTime;

public record FaqUsageTrendPoint(
		LocalDateTime bucketStart,
		LocalDateTime bucketEnd,
		long count
) {
}
