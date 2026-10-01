package com.ubot.ranking.dto.model;

import com.ubot.ranking.enums.RegionSido;

public record TrendRankingItem(
		Long rank,
		Long faqId,
		String question,
		String answer,
		Long currentCount,
		Long baselineTotalCount,
		Double baselineAverageCount,
		Double trendRatio,
		RegionSido region
) {
}
