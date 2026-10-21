package com.ubot.monitoring.faqmonitoring.dto.model.improvelayer;

import java.util.List;

public record DuplicateSuspectedAssessment(
		Long canonicalFaqId,
		List<DuplicateCandidateAssessment> candidateAssessments
) {
}
