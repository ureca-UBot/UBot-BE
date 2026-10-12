package com.ubot.monitoring.dto.model.detail;

import com.ubot.faq.enums.Intent;

public record CooccurringFaqItem(
		Long faqId,
		String question,
		Intent intent,
		long coOccurrenceCount,
		double coOccurrenceRate
) {
}
