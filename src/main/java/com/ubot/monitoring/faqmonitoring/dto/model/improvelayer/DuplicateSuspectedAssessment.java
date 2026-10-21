package com.ubot.monitoring.faqmonitoring.dto.model.improvelayer;

import java.util.List;

public record DuplicateSuspectedAssessment(
		List<DuplicateCandidateAssessment> candidateAssessments
) {
}
