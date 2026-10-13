package com.ubot.monitoring.faqmonitoring.dto.model.detail;

import java.time.LocalDateTime;

public record FaqTrendWindow(
		FaqUsageTrendPeriod period,
		LocalDateTime startAt,
		LocalDateTime endAt,
		int bucketCount
) {
	public static FaqTrendWindow from(
			FaqUsageTrendPeriod period,
			LocalDateTime requestedAsOf,
			LocalDateTime now
	) {
		LocalDateTime currentStart = align(period, now);
		LocalDateTime requestedStart = align(period, requestedAsOf == null ? now : requestedAsOf);
		LocalDateTime startAt = requestedStart.isAfter(currentStart) ? currentStart : requestedStart;

		return switch (period) {
			case SIX_HOURS -> new FaqTrendWindow(period, startAt, startAt.plusHours(6), 24);
			case DAY -> new FaqTrendWindow(period, startAt, startAt.plusDays(1), 24);
			case MONTH -> new FaqTrendWindow(period, startAt, startAt.plusMonths(1), startAt.toLocalDate().lengthOfMonth());
			case YEAR -> new FaqTrendWindow(period, startAt, startAt.plusYears(1), 12);
		};
	}

	private static LocalDateTime align(FaqUsageTrendPeriod period, LocalDateTime value) {
		return switch (period) {
			case SIX_HOURS -> value.toLocalDate().atStartOfDay().plusHours((value.getHour() / 6) * 6L);
			case DAY -> value.toLocalDate().atStartOfDay();
			case MONTH -> value.toLocalDate().withDayOfMonth(1).atStartOfDay();
			case YEAR -> value.toLocalDate().withDayOfYear(1).atStartOfDay();
		};
	}
}
