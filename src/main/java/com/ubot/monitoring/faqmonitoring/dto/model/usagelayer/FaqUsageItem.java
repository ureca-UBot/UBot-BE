package com.ubot.monitoring.faqmonitoring.dto.model.usagelayer;

import com.ubot.faq.enums.Intent;

public record FaqUsageItem(
		Long faqId,
		String question,
		Intent intent,
		long count
) {
}
