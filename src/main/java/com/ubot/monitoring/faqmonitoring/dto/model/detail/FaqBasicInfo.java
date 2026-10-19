package com.ubot.monitoring.faqmonitoring.dto.model.detail;

import com.ubot.faq.enums.Intent;
import java.time.LocalDateTime;

public record FaqBasicInfo(
		Long faqId,
		String question,
		String answer,
		String categoryName,
		Intent intent,
		Integer version,
		LocalDateTime createdAt,
		LocalDateTime updatedAt
) {
}
