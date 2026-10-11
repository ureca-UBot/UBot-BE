package com.ubot.monitoring.repository;

import com.ubot.faq.enums.Intent;
import com.ubot.monitoring.dto.model.detail.CooccurringFaqItem;
import com.ubot.monitoring.dto.model.detail.FaqSimilaritySummary;
import com.ubot.monitoring.dto.model.detail.FaqTop1Summary;
import com.ubot.monitoring.dto.model.detail.FaqTopGapSummary;
import com.ubot.monitoring.dto.model.detail.FaqTrendWindow;
import com.ubot.monitoring.dto.model.detail.FaqUsageTrendPoint;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
public class FaqMonitoringDetailQueryRepository {

	private static final int CO_OCCURRENCE_LIMIT = 5;
	private final NamedParameterJdbcTemplate jdbcTemplate;

	public List<FaqUsageTrendPoint> findUsageTrend(Long faqId, FaqTrendWindow window) {
		String sql = """
				WITH buckets AS (
					SELECT
						:startAt + (n * interval '%s') AS bucket_start,
						:startAt + ((n + 1) * interval '%s') AS bucket_end
					FROM generate_series(0, :bucketCount - 1) AS n
				)
				SELECT b.bucket_start, b.bucket_end, COUNT(fl.id) AS usage_count
				FROM buckets b
				LEFT JOIN question_log ql
					ON ql.created_at >= b.bucket_start
					AND ql.created_at < b.bucket_end
				LEFT JOIN faq_log fl
					ON fl.question_log_id = ql.id
					AND fl.faq_id = :faqId
				GROUP BY b.bucket_start, b.bucket_end
				ORDER BY b.bucket_start
				""".formatted(bucketInterval(window), bucketInterval(window));

		return jdbcTemplate.query(sql, parameters(faqId, window), (rs, rowNum) -> new FaqUsageTrendPoint(
				rs.getTimestamp("bucket_start").toLocalDateTime(),
				rs.getTimestamp("bucket_end").toLocalDateTime(),
				rs.getLong("usage_count")
		));
	}

	public FaqSimilaritySummary findSimilaritySummary(Long faqId, FaqTrendWindow window) {
		String sql = """
				SELECT COALESCE(AVG(fl.similarity), 0.0) AS average_similarity,
					COUNT(fl.similarity) AS sample_count
				FROM faq_log fl
				JOIN question_log ql ON ql.id = fl.question_log_id
				WHERE fl.faq_id = :faqId
					AND ql.created_at >= :startAt
					AND ql.created_at < :endAt
					AND fl.similarity IS NOT NULL
				""";
		return jdbcTemplate.queryForObject(sql, parameters(faqId, window), (rs, rowNum) -> new FaqSimilaritySummary(
				rs.getDouble("average_similarity"), rs.getLong("sample_count")));
	}

	public FaqTop1Summary findTop1Summary(Long faqId, FaqTrendWindow window) {
		String sql = """
				WITH top1_counts AS (
					SELECT f.id, COUNT(ql.id) FILTER (WHERE fl.rank = 1) AS top1_count
					FROM faq f
					LEFT JOIN faq_log fl ON fl.faq_id = f.id
					LEFT JOIN question_log ql ON ql.id = fl.question_log_id
						AND ql.created_at >= :startAt
						AND ql.created_at < :endAt
					WHERE f.deleted_at IS NULL
					GROUP BY f.id
				), target AS (
					SELECT
						COUNT(ql.id) FILTER (WHERE fl.rank = 1) AS top1_count,
						COUNT(ql.id) AS usage_count
					FROM faq f
					LEFT JOIN faq_log fl ON fl.faq_id = f.id
					LEFT JOIN question_log ql ON ql.id = fl.question_log_id
						AND ql.created_at >= :startAt
						AND ql.created_at < :endAt
					WHERE f.id = :faqId AND f.deleted_at IS NULL
				)
				SELECT t.top1_count,
					COALESCE(t.top1_count::double precision / NULLIF(t.usage_count, 0), 0.0) AS top1_rate,
					COALESCE((SELECT AVG(c.top1_count) FROM top1_counts c), 0.0) AS overall_average_top1_count
				FROM target t
				""";
		return jdbcTemplate.queryForObject(sql, parameters(faqId, window), (rs, rowNum) -> new FaqTop1Summary(
				rs.getLong("top1_count"), rs.getDouble("top1_rate"), rs.getDouble("overall_average_top1_count")));
	}

	public FaqTopGapSummary findTopGapSummary(Long faqId, FaqTrendWindow window) {
		String sql = """
				SELECT COALESCE(AVG(first_log.similarity - second_log.similarity), 0.0) AS average_gap,
					COUNT(*) AS sample_count
				FROM faq_log first_log
				JOIN faq_log second_log
					ON second_log.question_log_id = first_log.question_log_id
					AND second_log.rank = 2
				JOIN question_log ql ON ql.id = first_log.question_log_id
				WHERE first_log.faq_id = :faqId
					AND first_log.rank = 1
					AND first_log.similarity IS NOT NULL
					AND second_log.similarity IS NOT NULL
					AND ql.created_at >= :startAt
					AND ql.created_at < :endAt
				""";
		return jdbcTemplate.queryForObject(sql, parameters(faqId, window), (rs, rowNum) -> new FaqTopGapSummary(
				rs.getDouble("average_gap"), rs.getLong("sample_count")));
	}

	public List<CooccurringFaqItem> findCooccurringFaqs(Long faqId, FaqTrendWindow window) {
		String sql = """
				WITH target_logs AS (
					SELECT DISTINCT fl.question_log_id
					FROM faq_log fl
					JOIN question_log ql ON ql.id = fl.question_log_id
					WHERE fl.faq_id = :faqId
						AND ql.created_at >= :startAt
						AND ql.created_at < :endAt
				)
				SELECT other_log.faq_id, other_faq.question, other_faq.intent,
					COUNT(*) AS co_occurrence_count,
					COUNT(*)::double precision / NULLIF((SELECT COUNT(*) FROM target_logs), 0) AS co_occurrence_rate
				FROM target_logs target
				JOIN faq_log other_log
					ON other_log.question_log_id = target.question_log_id
					AND other_log.faq_id <> :faqId
				JOIN faq other_faq
					ON other_faq.id = other_log.faq_id
					AND other_faq.deleted_at IS NULL
				GROUP BY other_log.faq_id, other_faq.question, other_faq.intent
				ORDER BY co_occurrence_count DESC, other_log.faq_id ASC
				LIMIT %d
				""".formatted(CO_OCCURRENCE_LIMIT);

		return jdbcTemplate.query(sql, parameters(faqId, window), (rs, rowNum) -> new CooccurringFaqItem(
				rs.getLong("faq_id"), rs.getString("question"), Intent.valueOf(rs.getString("intent")),
				rs.getLong("co_occurrence_count"), rs.getDouble("co_occurrence_rate")
		));
	}

	private MapSqlParameterSource parameters(Long faqId, FaqTrendWindow window) {
		return new MapSqlParameterSource()
				.addValue("faqId", faqId)
				.addValue("startAt", window.startAt())
				.addValue("endAt", window.endAt())
				.addValue("bucketCount", window.bucketCount());
	}

	private String bucketInterval(FaqTrendWindow window) {
		return switch (window.period()) {
			case SIX_HOURS -> "15 minutes";
			case DAY -> "1 hour";
			case MONTH -> "1 day";
			case YEAR -> "1 month";
		};
	}
}
