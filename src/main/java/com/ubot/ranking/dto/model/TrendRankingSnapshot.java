package com.ubot.ranking.dto.model;

import com.ubot.ranking.enums.RegionSido;

import java.time.LocalDateTime;
import java.util.List;

public record TrendRankingSnapshot(
		LocalDateTime calculatedAt,
		List<TrendRankingItem>  rankings
) implements RankingSnapshot {
}
