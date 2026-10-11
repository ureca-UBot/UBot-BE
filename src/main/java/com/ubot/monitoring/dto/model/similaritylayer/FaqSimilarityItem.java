package com.ubot.monitoring.dto.model.similaritylayer;

import com.ubot.faq.enums.Intent;

public record FaqSimilarityItem(
		Long faqId,
		String question,
		Intent intent,
		double averageSimilarity
) {
}
