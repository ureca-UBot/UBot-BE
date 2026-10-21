package com.ubot.monitoring.faqmonitoring.dto.model.improvelayer;

public record DuplicateCandidateAssessment(
		Long candidateFaqId,
		DuplicateReviewResult result,
		String reason
) {
}
