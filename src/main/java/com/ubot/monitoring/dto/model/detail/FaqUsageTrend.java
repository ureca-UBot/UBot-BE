package com.ubot.monitoring.dto.model.detail;

import java.time.LocalDateTime;
import java.util.List;

public record FaqUsageTrend(
		FaqUsageTrendPeriod period,
		LocalDateTime periodStart,
		LocalDateTime periodEnd,
		long totalCount,
		List<FaqUsageTrendPoint> points
) {
}
