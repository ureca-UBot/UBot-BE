package com.ubot.monitoring.faqmonitoring.dto.model;

public record UnansweredQuestionSummary(
		long totalQuestionCount,
		long unansweredQuestionCount,
		double unansweredRate
) {
}
