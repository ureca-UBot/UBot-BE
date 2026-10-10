package com.ubot.monitoring.dto.model.similaritylayer;

public record FaqSimilarityItem(
		Long faqId,
		String question,
		double averageSimilarity
) {
}
