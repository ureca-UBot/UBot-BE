package com.ubot.monitoring.dto.response;

import com.ubot.monitoring.dto.model.similaritylayer.FaqSimilarityRanking;
import com.ubot.monitoring.dto.model.usagelayer.FaqUsageRanking;

public record FaqMonitoringResponse(
		FaqUsageRanking faqUsageRanking,
		FaqSimilarityRanking faqSimilarityRanking
) {
}
