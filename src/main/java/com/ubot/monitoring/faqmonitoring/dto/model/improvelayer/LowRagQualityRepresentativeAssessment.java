package com.ubot.monitoring.faqmonitoring.dto.model.improvelayer;

public record LowRagQualityRepresentativeAssessment(
		Boolean needsUpdate,
		String reason,
		String suggestedQuestion
) {
}
