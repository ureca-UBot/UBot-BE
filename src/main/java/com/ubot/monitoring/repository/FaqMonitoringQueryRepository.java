package com.ubot.monitoring.repository;

import com.ubot.faq.enums.Intent;
import com.ubot.monitoring.dto.model.usagelayer.FaqUsageByIntent;
import com.ubot.monitoring.dto.model.usagelayer.FaqUsageItem;
import com.ubot.monitoring.dto.model.usagelayer.FaqUsageRanking;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
public class FaqMonitoringQueryRepository {

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

	private FaqUsageByIntent toDto(Intent intent, ScopeRows rows) {
		return new FaqUsageByIntent(intent, rows == null ? List.of() : rows.top, rows == null ? List.of() : rows.low);
	}

	private static final class ScopeRows {
		private final List<FaqUsageItem> top = new java.util.ArrayList<>();
		private final List<FaqUsageItem> low = new java.util.ArrayList<>();
	}
}
