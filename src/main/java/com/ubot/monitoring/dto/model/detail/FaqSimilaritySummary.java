package com.ubot.monitoring.dto.model.detail;

public record FaqSimilaritySummary(
		double averageSimilarity,
		long sampleCount
) {
}
