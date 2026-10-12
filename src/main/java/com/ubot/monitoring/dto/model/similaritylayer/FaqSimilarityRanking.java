package com.ubot.monitoring.dto.model.similaritylayer;

import com.ubot.monitoring.dto.model.usagelayer.FaqUsageByIntent;

public record FaqSimilarityRanking(
		double overallAverageSimilarity,
		FaqSimilarityByIntent all,
		FaqSimilarityByIntent general,
		FaqSimilarityByIntent userData,
		FaqSimilarityByIntent storeData

) {
}
