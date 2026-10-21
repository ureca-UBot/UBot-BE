package com.ubot.monitoring.faqmonitoring.dto.model.improvelayer;

import java.util.List;

public record DuplicateSuspectedAssessment(
		String recommendation,
		List<DuplicateCandidateAssessment> candidateAssessments
) {
}
