package com.ubot.ranking.dto.model;

public record PopularRankingItem(
		Long rank,
		Long faqId,
		String question,
		String answer,
		Long currentCount
) {
}
