package com.ubot.monitoring.faqmonitoring.dto.model.improvelayer;

public record DuplicateCandidateAssessment(
		Long candidateFaqId,
		DuplicateReviewResult result,
		DuplicateReviewAction action,
		String reason,
		String targetFaqRecommendation,
		String candidateFaqRecommendation
) {
}
