package com.ubot.monitoring.faqmonitoring.dto.model.improvelayer;

public record DuplicateCandidateAssessment(
		Long candidateFaqId,
		String representativeQuestion,
		DuplicateReviewResult result,
		DuplicateReviewAction action,
		String reason,
		String targetFaqRecommendation,
		String candidateFaqRecommendation
) {
}
