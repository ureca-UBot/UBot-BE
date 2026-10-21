package com.ubot.monitoring.faqmonitoring.dto.model.improvelayer;

public record LowRagQualityRepresentativeAssessment(
		boolean needsUpdate,
		String reason,
		String suggestedQuestion
) {
}
