package com.ubot.monitoring.dto.model;

public record UnansweredQuestionSummary(
		long totalQuestionCount,
		long unansweredQuestionCount,
		double unansweredRate
) {
}
