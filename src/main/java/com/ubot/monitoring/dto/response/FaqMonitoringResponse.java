package com.ubot.monitoring.dto.response;

import com.ubot.monitoring.dto.model.similaritylayer.FaqSimilarityRanking;
import com.ubot.monitoring.dto.model.usagelayer.FaqUsageRanking;
import com.ubot.monitoring.dto.model.UnansweredQuestionSummary;

public record FaqMonitoringResponse(
		UnansweredQuestionSummary unansweredQuestionSummary,
		FaqUsageRanking faqUsageRanking,
		FaqSimilarityRanking faqSimilarityRanking
) {
}
