package com.ubot.monitoring.faqmonitoring.dto.model.improvelayer;

public record DuplicateCandidateAssessment(
		Long candidateFaqId,
		String representativeQuestion,
		String result,
		String reason,
		String targetFaqRecommendation,
		String candidateFaqRecommendation
) {
}
