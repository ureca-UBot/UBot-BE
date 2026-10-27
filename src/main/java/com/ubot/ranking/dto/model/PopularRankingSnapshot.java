package com.ubot.ranking.dto.model;

import java.time.LocalDateTime;
import java.util.List;

public record PopularRankingSnapshot(
		LocalDateTime calculatedAt,
		List<PopularRankingItem>  rankings
) implements RankingSnapshot {
}
