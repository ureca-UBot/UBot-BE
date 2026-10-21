package com.ubot.monitoring.faqmonitoring.dto.model.improvelayer;

public record LowRagQualityQuestionAssessment(
		int questionNumber,
		boolean answerableByCurrentFaq
) {
}
