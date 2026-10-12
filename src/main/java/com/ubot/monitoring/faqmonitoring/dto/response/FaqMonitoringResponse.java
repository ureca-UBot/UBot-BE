package com.ubot.monitoring.faqmonitoring.dto.response;

import com.ubot.monitoring.faqmonitoring.dto.model.similaritylayer.FaqSimilarityRanking;
import com.ubot.monitoring.faqmonitoring.dto.model.usagelayer.FaqUsageRanking;
import com.ubot.monitoring.faqmonitoring.dto.model.UnansweredQuestionSummary;

public record FaqMonitoringResponse(
		UnansweredQuestionSummary unansweredQuestionSummary,
		FaqUsageRanking faqUsageRanking,
		FaqSimilarityRanking faqSimilarityRanking
) {
}
