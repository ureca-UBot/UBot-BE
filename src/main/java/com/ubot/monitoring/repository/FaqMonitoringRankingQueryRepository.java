package com.ubot.monitoring.repository;

import com.ubot.faq.enums.Intent;
import com.ubot.monitoring.dto.model.usagelayer.FaqUsageByIntent;
import com.ubot.monitoring.dto.model.usagelayer.FaqUsageItem;
import com.ubot.monitoring.dto.model.usagelayer.FaqUsageRanking;
import com.ubot.monitoring.dto.model.similaritylayer.FaqSimilarityByIntent;
import com.ubot.monitoring.dto.model.similaritylayer.FaqSimilarityItem;
import com.ubot.monitoring.dto.model.similaritylayer.FaqSimilarityRanking;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
public class FaqMonitoringRankingQueryRepository {

	private final NamedParameterJdbcTemplate jdbcTemplate;

	/**
	 * PostgreSQL 전용 구현입니다. 집계, 전체·의도별 범위, Top/Low 순위를
	 * 한 번의 DB 왕복으로 계산합니다.
	 */
	public FaqUsageRanking findUsageRanking(
			LocalDateTime startAt,
			LocalDateTime endAt,
			int limit
	) {
		String sql = """
				WITH usage_counts AS (
					SELECT f.id AS faq_id, f.question, f.intent, COUNT(ql.id) AS usage_count
					FROM faq f
					LEFT JOIN faq_log fl ON fl.faq_id = f.id
					LEFT JOIN question_log ql ON ql.id = fl.question_log_id
						AND ql.created_at >= :startAt
						AND ql.created_at < :endAt
					WHERE f.deleted_at IS NULL
					GROUP BY f.id, f.question, f.intent
				), scoped_counts AS (
					SELECT 'ALL' AS scope, faq_id, question, intent, usage_count FROM usage_counts
					UNION ALL
					SELECT intent::text AS scope, faq_id, question, intent, usage_count FROM usage_counts
				), ranked AS (
					SELECT *,
						ROW_NUMBER() OVER (PARTITION BY scope ORDER BY usage_count DESC, faq_id ASC) AS top_rank,
						ROW_NUMBER() OVER (PARTITION BY scope ORDER BY usage_count ASC, faq_id ASC) AS low_rank
					FROM scoped_counts
				)
				SELECT scope, 'TOP' AS ranking_type, faq_id, question, intent, usage_count, top_rank AS rank_in_type
				FROM ranked WHERE top_rank <= :limit
				UNION ALL
				SELECT scope, 'LOW' AS ranking_type, faq_id, question, intent, usage_count, low_rank AS rank_in_type
				FROM ranked WHERE low_rank <= :limit
				ORDER BY scope, ranking_type, rank_in_type
				""";

		MapSqlParameterSource parameters = new MapSqlParameterSource()
				.addValue("startAt", startAt)
				.addValue("endAt", endAt)
				.addValue("limit", limit);
		Map<String, ScopeRows> rows = new java.util.HashMap<>();
		jdbcTemplate.query(sql, parameters, rs -> {
			String scope = rs.getString("scope");
			ScopeRows scopeRows = rows.computeIfAbsent(scope, ignored -> new ScopeRows());
			FaqUsageItem item = new FaqUsageItem(
					rs.getLong("faq_id"),
					rs.getString("question"),
					Intent.valueOf(rs.getString("intent")),
					rs.getLong("usage_count")
			);
			if ("TOP".equals(rs.getString("ranking_type"))) {
				scopeRows.top.add(item);
			} else {
				scopeRows.low.add(item);
			}
		});

		return new FaqUsageRanking(
				toDto(null, rows.get("ALL")),
				toDto(Intent.GENERAL, rows.get(Intent.GENERAL.name())),
				toDto(Intent.USER_DATA, rows.get(Intent.USER_DATA.name())),
				toDto(Intent.STORE_DATA, rows.get(Intent.STORE_DATA.name()))
		);
	}

	/**
	 * 기간 내 실제로 기록된 유사도를 FAQ별 평균으로 집계합니다.
	 * 유사도 값이 없는 FAQ는 평균을 계산할 수 없으므로 Top/Low 순위에서 제외합니다.
	 */
	public FaqSimilarityRanking findSimilarityRanking(
			LocalDateTime startAt,
			LocalDateTime endAt,
			int limit
	) {
		String sql = """
				WITH similarity_counts AS (
					SELECT f.id AS faq_id, f.question, f.intent,
						AVG(fl.similarity) AS average_similarity
					FROM faq f
					JOIN faq_log fl ON fl.faq_id = f.id
					JOIN question_log ql ON ql.id = fl.question_log_id
					WHERE f.deleted_at IS NULL
						AND ql.created_at >= :startAt
						AND ql.created_at < :endAt
						AND fl.similarity IS NOT NULL
					GROUP BY f.id, f.question, f.intent
				), scoped_counts AS (
					SELECT 'ALL' AS scope, faq_id, question, intent, average_similarity FROM similarity_counts
					UNION ALL
					SELECT intent::text AS scope, faq_id, question, intent, average_similarity FROM similarity_counts
				), ranked AS (
					SELECT *,
						ROW_NUMBER() OVER (PARTITION BY scope ORDER BY average_similarity DESC, faq_id ASC) AS top_rank,
						ROW_NUMBER() OVER (PARTITION BY scope ORDER BY average_similarity ASC, faq_id ASC) AS low_rank
					FROM scoped_counts
				), overall AS (
					SELECT COALESCE(AVG(fl.similarity), 0.0) AS overall_average_similarity
					FROM faq f
					JOIN faq_log fl ON fl.faq_id = f.id
					JOIN question_log ql ON ql.id = fl.question_log_id
					WHERE f.deleted_at IS NULL
						AND ql.created_at >= :startAt
						AND ql.created_at < :endAt
						AND fl.similarity IS NOT NULL
				)
				SELECT 'SUMMARY' AS row_type, NULL::text AS scope, NULL::text AS ranking_type,
					NULL::bigint AS faq_id, NULL::text AS question, NULL::text AS intent,
					NULL::double precision AS average_similarity, overall_average_similarity, 0 AS rank_in_type
				FROM overall
				UNION ALL
				SELECT 'RANKING' AS row_type, scope, 'TOP' AS ranking_type,
					faq_id, question, intent, average_similarity, NULL::double precision, top_rank
				FROM ranked WHERE top_rank <= :limit
				UNION ALL
				SELECT 'RANKING' AS row_type, scope, 'LOW' AS ranking_type,
					faq_id, question, intent, average_similarity, NULL::double precision, low_rank
				FROM ranked WHERE low_rank <= :limit
				ORDER BY row_type, scope, ranking_type, rank_in_type
				""";

		MapSqlParameterSource parameters = new MapSqlParameterSource()
				.addValue("startAt", startAt)
				.addValue("endAt", endAt)
				.addValue("limit", limit);
		Map<String, SimilarityScopeRows> rows = new java.util.HashMap<>();
		double[] overallAverage = {0.0};
		jdbcTemplate.query(sql, parameters, rs -> {
			if ("SUMMARY".equals(rs.getString("row_type"))) {
				overallAverage[0] = rs.getDouble("overall_average_similarity");
				return;
			}

			SimilarityScopeRows scopeRows = rows.computeIfAbsent(
					rs.getString("scope"), ignored -> new SimilarityScopeRows());
			FaqSimilarityItem item = new FaqSimilarityItem(
					rs.getLong("faq_id"),
					rs.getString("question"),
					Intent.valueOf(rs.getString("intent")),
					rs.getDouble("average_similarity")
			);
			if ("TOP".equals(rs.getString("ranking_type"))) {
				scopeRows.top.add(item);
			} else {
				scopeRows.low.add(item);
			}
		});


		return new FaqSimilarityRanking(
				overallAverage[0],
				toSimilarityDto(rows.get("ALL")),
				toSimilarityDto(rows.get(Intent.GENERAL.name())),
				toSimilarityDto(rows.get(Intent.USER_DATA.name())),
				toSimilarityDto(rows.get(Intent.STORE_DATA.name()))
		);
	}

	private FaqUsageByIntent toDto(Intent intent, ScopeRows rows) {
		return new FaqUsageByIntent(intent, rows == null ? List.of() : rows.top, rows == null ? List.of() : rows.low);
	}

	private FaqSimilarityByIntent toSimilarityDto(SimilarityScopeRows rows) {
		return new FaqSimilarityByIntent(rows == null ? List.of() : rows.top, rows == null ? List.of() : rows.low);
	}

	private static final class ScopeRows {
		private final List<FaqUsageItem> top = new java.util.ArrayList<>();
		private final List<FaqUsageItem> low = new java.util.ArrayList<>();
	}

	private static final class SimilarityScopeRows {
		private final List<FaqSimilarityItem> top = new java.util.ArrayList<>();
		private final List<FaqSimilarityItem> low = new java.util.ArrayList<>();
	}
}
