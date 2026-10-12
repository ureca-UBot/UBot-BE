package com.ubot.monitoring.faqmonitoring.dto.model.similaritylayer;

public record FaqSimilarityRanking(
		double overallAverageSimilarity,
		FaqSimilarityByIntent all,
		FaqSimilarityByIntent general,
		FaqSimilarityByIntent userData,
		FaqSimilarityByIntent storeData

) {
}
