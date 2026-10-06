package com.ubot.ranking.service;

import com.ubot.ranking.dto.model.*;
import com.ubot.ranking.dto.response.RankingResponseDto;
import com.ubot.ranking.entity.RegionalTrendRankingPolicy;
import com.ubot.ranking.enums.RegionSido;
import com.ubot.ranking.repository.RankingQueryRepository;
import com.ubot.ranking.repository.RankingRedisRepository;
import com.ubot.ranking.repository.RegionalTrendRankingPolicyRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class RankingService {
	private final RankingQueryRepository rankingQueryRepository;
	private final RankingRedisRepository rankingRedisRepository;
	private final RegionalTrendRankingPolicyRepository regionalTrendRankingPolicyRepository;

	@Value("${ranking.window-minutes}")
	private int rankingWindowMinutes;

	@Value("${ranking.size}")
	private int rankingSize;

	@Value("${ranking.min-current-search-count}")
	private int minCurrentSearchCount;

	@Value("${ranking.baseline-period-days}")
	private int baselinePeriodDays;

	@Value("${ranking.baseline-min-sample-count}")
	private int baselineMinSampleCount;

	@Value("${ranking.trend-ratio-threshold}")
	private double trendRatioThreshold;

	public void refreshRanking() {
		LocalDateTime calculatedAt = LocalDateTime.now();
		LocalDateTime startAt = calculatedAt.minusMinutes(rankingWindowMinutes);

		Map<String, RankingSnapshot> snapshots = new HashMap<>();


		// 인기 FAQ 집계
		List<PopularRankingItem> popular = rankingQueryRepository.aggregatePopularRanking(
				startAt,
				calculatedAt,
				rankingSize,
				minCurrentSearchCount
		);
		PopularRankingSnapshot popularSnapshot = new PopularRankingSnapshot(calculatedAt, popular);
		snapshots.put(RankingRedisKey.popular(), popularSnapshot);

		// 전체 급상승 FAQ 집계
		List<TrendRankingItem> globalTrend = rankingQueryRepository.aggregateTrendingRanking(
				startAt,
				calculatedAt,
				rankingWindowMinutes,
				rankingSize,
				minCurrentSearchCount,
				baselinePeriodDays,
				baselineMinSampleCount,
				trendRatioThreshold,
				null
				);
		TrendRankingSnapshot globalTrendSnapshot = new TrendRankingSnapshot(calculatedAt, globalTrend);
		snapshots.put(RankingRedisKey.trend(), globalTrendSnapshot);

		List<RegionalTrendRankingPolicy> regionalPolicies = regionalTrendRankingPolicyRepository.findAll();
		Map<RegionSido, RegionalTrendRankingPolicy> regionPoliciesMap =
				regionalPolicies.stream()
								.collect(Collectors.toMap(
									RegionalTrendRankingPolicy::getRegionName,
									Function.identity()
								));


		// 지역별 급상승 FAQ 집계
		for(RegionSido region :  RegionSido.values()) {
			RegionalTrendRankingPolicy regionPolicy = regionPoliciesMap.get(region);

			int regionalMinCurrentSearchCount = regionPolicy != null ?  regionPolicy.getMinCurrentSearchCount() : minCurrentSearchCount;
			int regionalMinBaselineSampleCount = regionPolicy != null ?  regionPolicy.getMinBaselineSampleCount() : baselineMinSampleCount;
			double regionalTrendRatioThreshold =  regionPolicy != null ?  regionPolicy.getTrendRatioThreshold() : trendRatioThreshold;

			List<TrendRankingItem> regionalTrend = rankingQueryRepository.aggregateTrendingRanking(
					startAt,
					calculatedAt,
					rankingWindowMinutes,
					rankingSize,
					regionalMinCurrentSearchCount,
					baselinePeriodDays,
					regionalMinBaselineSampleCount,
					regionalTrendRatioThreshold,
					region
			);
			TrendRankingSnapshot regionTrendSnapshot = new TrendRankingSnapshot(calculatedAt, regionalTrend);
			snapshots.put(RankingRedisKey.trend(region), regionTrendSnapshot);
		}
		rankingRedisRepository.saveSnapshots(snapshots);
	}

	public RankingResponseDto getRanking() {
		Map<String, RankingSnapshot> snapshots =
				rankingRedisRepository.findSnapshots();

		PopularRankingSnapshot popular =
				(PopularRankingSnapshot) snapshots.get(
						RankingRedisKey.popular()
				);

		TrendRankingSnapshot trend =
				(TrendRankingSnapshot) snapshots.get(
						RankingRedisKey.trend()
				);

		Map<RegionSido, TrendRankingSnapshot> regionalTrend = new EnumMap<>(RegionSido.class);

		for (RegionSido region : RegionSido.values()) {
			TrendRankingSnapshot regionRankingSnapshot = (TrendRankingSnapshot) snapshots.get(RankingRedisKey.trend(region));
			regionalTrend.put(region, regionRankingSnapshot);
		}

		return new RankingResponseDto(
				popular,
				trend,
				regionalTrend
		);
	}
}
