package com.ubot.monitoring.faqmonitoring.dto.model.similaritylayer;

import java.util.List;

public record FaqSimilarityByIntent(
		List<FaqSimilarityItem> top,
		List<FaqSimilarityItem> low
){
}
