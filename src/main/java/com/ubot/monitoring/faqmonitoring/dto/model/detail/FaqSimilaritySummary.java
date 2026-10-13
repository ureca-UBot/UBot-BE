package com.ubot.monitoring.faqmonitoring.dto.model.detail;

public record FaqSimilaritySummary(
		double averageSimilarity,
		long sampleCount
) {
}
