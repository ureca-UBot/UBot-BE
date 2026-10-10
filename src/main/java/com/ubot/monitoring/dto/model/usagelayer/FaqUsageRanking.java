package com.ubot.monitoring.dto.model.usagelayer;

public record FaqUsageRanking(
		FaqUsageByIntent all,
		FaqUsageByIntent general,
		FaqUsageByIntent userData,
		FaqUsageByIntent storeData
) {
}
