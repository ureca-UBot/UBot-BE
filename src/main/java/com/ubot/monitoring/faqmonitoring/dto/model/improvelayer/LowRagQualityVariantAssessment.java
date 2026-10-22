package com.ubot.monitoring.faqmonitoring.dto.model.improvelayer;

import java.util.List;

public record LowRagQualityVariantAssessment(
		List<LowRagQualityQuestionAssessment> questionAssessments
) {
}
