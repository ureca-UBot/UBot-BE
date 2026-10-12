package com.ubot.monitoring.dto.model.similaritylayer;

import java.util.List;

public record FaqSimilarityByIntent(
		List<FaqSimilarityItem> top,
		List<FaqSimilarityItem> low
){
}
