package com.ubot.monitoring.faqmonitoring.dto.model.usagelayer;

public record FaqUsageRanking(
		FaqUsageByIntent all,
		FaqUsageByIntent general,
		FaqUsageByIntent userData,
		FaqUsageByIntent storeData,
		FaqUsageByIntent productData
) {
}
