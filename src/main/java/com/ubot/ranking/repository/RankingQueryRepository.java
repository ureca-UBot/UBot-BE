package com.ubot.ranking.repository;

import com.ubot.ranking.dto.model.PopularRankingItem;
import com.ubot.ranking.dto.model.TrendRankingItem;
import com.ubot.ranking.enums.RegionSido;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

@Repository
@RequiredArgsConstructor
public class RankingQueryRepository {
	private final NamedParameterJdbcTemplate jdbcTemplate;

	public List<PopularRankingItem> aggregatePopularRanking(
			LocalDateTime startAt,
			LocalDateTime endAt,
			int rankingSize,
			int minCurrentSearchCount
	){
		String sql= """
				WITH
				current_counts AS (
					SELECT
					    fl.faq_id,
					    COUNT(*) AS current_count
					FROM faq_log fl
					JOIN question_log ql
					ON ql.id = fl.question_log_id
					WHERE
					    ql.created_at >= :startAt
					AND ql.created_at < :endAt
					AND ql.ranking_eligible = true
					GROUP BY fl.faq_id
					HAVING COUNT(*) >= :minCurrentSearchCount
				)
				SELECT 
				    ROW_NUMBER() OVER (
				    	ORDER BY
				    		c.current_count DESC, 
					    	c.faq_id ASC
				    ) AS rank,
					c.faq_id,
					f.question,
					f.answer,
					c.current_count
				FROM current_counts c
				JOIN faq f
				    ON f.id = c.faq_id
					AND f.deleted_at IS NULL
				ORDER BY
					 c.current_count DESC,
					 c.faq_id ASC
				LIMIT :rankingSize
				""";
		MapSqlParameterSource params = new MapSqlParameterSource()
				.addValue("startAt", startAt)
				.addValue("endAt", endAt)
				.addValue("minCurrentSearchCount", minCurrentSearchCount)
				.addValue("rankingSize", rankingSize);

		return jdbcTemplate.query(
				sql,
				params,
				(rs, rowNum) -> new PopularRankingItem(
						rs.getLong("rank"),
						rs.getLong("faq_id"),
						rs.getString("question"),
						rs.getString("answer"),
						rs.getLong("current_count")
				)
		);
	}

	public List<TrendRankingItem> aggregateTrendingRanking(
			LocalDateTime startAt,
			LocalDateTime endAt,
			int rankingWindowMinutes,
			int rankingSize,
			int minCurrentSearchCount,
			int baselinePeriodDays,
			int minBaselineSampleCount,
			double trendRatioThreshold,
			RegionSido region
	){
		LocalDateTime baselineStartAt = startAt.minusDays(baselinePeriodDays);
		LocalDateTime baselineEndAt = startAt;

		String regionCondition = region != null ? "AND ql.region = :region" : "";

		String sql= """
				WITH
				current_counts AS (
					SELECT
					    fl.faq_id,
					    COUNT(*) AS current_count
					FROM faq_log fl
					JOIN question_log ql
					ON ql.id = fl.question_log_id
					WHERE
					    ql.created_at >= :startAt
					AND ql.created_at < :endAt
					AND ql.ranking_eligible = true
					%s
					GROUP BY fl.faq_id
					HAVING COUNT(*) >= :minCurrentSearchCount
				),
				baseline_counts AS (
				    SELECT
				        fl.faq_id,
				        COUNT(*) AS baseline_total_count
				    FROM faq_log fl
				    JOIN question_log ql
				    	ON ql.id = fl.question_log_id
				    WHERE
				        ql.created_at >= :baselineStartAt
				    AND ql.created_at < :baselineEndAt
				    AND ql.ranking_eligible = true
				    %s
				    GROUP BY fl.faq_id
				    HAVING COUNT(*) >= :minBaselineSampleCount
				),
				averages AS (
				    SELECT
				        c.faq_id,
				        c.current_count,
				        b.baseline_total_count,
				        b.baseline_total_count::double precision * :rankingWindowMinutes
				        / (:baselinePeriodDays * 24 * 60) AS baseline_average_count
				    FROM current_counts c
				    JOIN baseline_counts b
				    	ON c.faq_id = b.faq_id
				),
				trends AS (
					SELECT
						*,
					    current_count / NULLIF(baseline_average_count, 0) AS trend_ratio
					FROM averages
				)
				SELECT
				    ROW_NUMBER() OVER (
				    	ORDER BY
					    	t.trend_ratio DESC,
					    	t.current_count DESC,
					    	t.faq_id ASC
				    ) AS rank,
					t.faq_id,
				    f.question,
				    f.answer,
				    t.current_count,
				    t.baseline_total_count,
				    t.baseline_average_count,
				    t.trend_ratio
				FROM trends t
				JOIN faq f 
					ON f.id = t.faq_id
					AND f.deleted_at IS NULL
				WHERE t.trend_ratio >= :trendRatioThreshold
				ORDER BY
				    t.trend_ratio DESC,
				    t.current_count DESC,
				    t.faq_id ASC
				LIMIT :rankingSize
				""".formatted(regionCondition, regionCondition);

		MapSqlParameterSource params = new MapSqlParameterSource()
				.addValue("startAt", startAt)
				.addValue("endAt", endAt)
				.addValue("minCurrentSearchCount", minCurrentSearchCount)
				.addValue("baselineStartAt", baselineStartAt)
				.addValue("baselineEndAt", baselineEndAt)
				.addValue("minBaselineSampleCount", minBaselineSampleCount)
				.addValue("rankingWindowMinutes", rankingWindowMinutes)
				.addValue("baselinePeriodDays", baselinePeriodDays)
				.addValue("trendRatioThreshold", trendRatioThreshold)
				.addValue("rankingSize", rankingSize);

		if(region != null)
			params.addValue("region", region.name());

		return jdbcTemplate.query(
				sql,
				params,
				(rs, rowNum) -> new TrendRankingItem(
						rs.getLong("rank"),
						rs.getLong("faq_id"),
						rs.getString("question"),
						rs.getString("answer"),
						rs.getLong("current_count"),
						rs.getLong("baseline_total_count"),
						rs.getDouble("baseline_average_count"),
						rs.getDouble("trend_ratio"),
						region
				)
		);
	}
}
