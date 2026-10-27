package com.ubot.ranking.dto.response;

import com.ubot.ranking.dto.model.PopularRankingSnapshot;
import com.ubot.ranking.dto.model.TrendRankingSnapshot;
import com.ubot.ranking.enums.RegionSido;

import java.util.Map;

public record RankingResponseDto (
		PopularRankingSnapshot popular,
		TrendRankingSnapshot trend,
		Map<RegionSido, TrendRankingSnapshot> regionalTrend
){
}
