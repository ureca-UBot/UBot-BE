package com.ubot.monitoring.dto.model.usagelayer;

public record FaqUsageItem(
		Long faqId,
		String question,
		long count
) {
}
