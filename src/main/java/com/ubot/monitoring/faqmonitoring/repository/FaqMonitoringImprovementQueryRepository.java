package com.ubot.monitoring.faqmonitoring.repository;

import com.ubot.common.PageResponseDto;
import com.ubot.faq.enums.Intent;
import com.ubot.monitoring.faqmonitoring.dto.model.improvelayer.FaqDuplicateCandidate;
import com.ubot.monitoring.faqmonitoring.dto.model.improvelayer.FaqImprovementItem;
import com.ubot.monitoring.faqmonitoring.dto.model.improvelayer.FaqImprovementType;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
public class FaqMonitoringImprovementQueryRepository {

	private final NamedParameterJdbcTemplate jdbcTemplate;

	/**
	 * 현재 FAQ 버전이 적용된 뒤의 후보 로그만 모아 개선 판단에 필요한 원본 지표를 반환합니다.
	 */
	public PageResponseDto<FaqImprovementItem> findImprovements(FaqImprovementSearchCriteria criteria) {
		String sql = """
				WITH current_version_logs AS (
					SELECT f.id AS faq_id, f.question, f.intent, f.version AS faq_version,
						f.updated_at AS version_started_at, fl.question_log_id, fl.rank, fl.similarity,
						ql.created_at AS question_created_at
					FROM faq f
					LEFT JOIN (
						faq_log fl
						JOIN question_log ql ON ql.id = fl.question_log_id
					) ON fl.faq_id = f.id
						AND fl.rank <= :topK
						AND ql.created_at >= f.updated_at
						AND ql.created_at < :evaluatedAt
					WHERE f.deleted_at IS NULL
				), metrics AS (
					SELECT faq_id, question, intent, faq_version, version_started_at,
						COUNT(question_log_id) AS top_k_count,
						COALESCE(AVG(similarity), 0.0) AS average_similarity,
						COUNT(question_log_id) FILTER (
							WHERE question_created_at >= :recentUsageStartAt
						) AS recent_usage_count
					FROM current_version_logs
					GROUP BY faq_id, question, intent, faq_version, version_started_at
				), top1_gaps AS (
					SELECT first_log.faq_id,
						first_log.similarity - second_log.similarity AS gap
					FROM current_version_logs first_log
					JOIN current_version_logs second_log
						ON second_log.question_log_id = first_log.question_log_id
						AND second_log.rank = 2
					WHERE first_log.rank = 1
						AND first_log.question_created_at IS NOT NULL
						AND first_log.similarity IS NOT NULL
						AND second_log.similarity IS NOT NULL
				), gap_metrics AS (
					SELECT faq_id, COUNT(*) AS top1_sample_count,
						COALESCE(AVG(gap), 0.0) AS average_top_gap,
						COALESCE(
							COUNT(*) FILTER (WHERE gap < :lowGapThreshold)::double precision / NULLIF(COUNT(*), 0),
							0.0
						) AS low_gap_rate
					FROM top1_gaps
					GROUP BY faq_id
				), classified AS (
					SELECT m.faq_id, m.question, m.intent, m.faq_version, m.version_started_at,
						m.top_k_count, m.average_similarity, COALESCE(g.top1_sample_count, 0) AS top1_sample_count,
						m.recent_usage_count, COALESCE(g.average_top_gap, 0.0) AS average_top_gap,
						COALESCE(g.low_gap_rate, 0.0) AS low_gap_rate,
						(m.top_k_count >= :minimumTopKSampleCount
							AND m.average_similarity < :lowSimilarityThreshold) AS low_rag_quality,
						(COALESCE(g.top1_sample_count, 0) >= :minimumTop1SampleCount
							AND COALESCE(g.low_gap_rate, 0.0) >= :lowGapRateThreshold) AS duplicate_suspected,
						(m.version_started_at <= :recentUsageStartAt
							AND m.recent_usage_count = 0) AS low_usage
					FROM metrics m
					LEFT JOIN gap_metrics g ON g.faq_id = m.faq_id
				), filtered AS (
					SELECT *, (low_rag_quality::int + duplicate_suspected::int + low_usage::int) AS type_count
					FROM classified
					WHERE (low_rag_quality OR duplicate_suspected OR low_usage)
						AND (:filterLowRagQuality = FALSE OR low_rag_quality)
						AND (:filterDuplicateSuspected = FALSE OR duplicate_suspected)
						AND (:filterLowUsage = FALSE OR low_usage)
				)
				SELECT totals.total_count, page_rows.*
				FROM (SELECT COUNT(*) AS total_count FROM filtered) totals
				LEFT JOIN LATERAL (
					SELECT * FROM filtered
					ORDER BY type_count DESC, top_k_count DESC, average_similarity ASC, faq_id ASC
					LIMIT :size OFFSET :offset
				) page_rows ON TRUE
				""";

		MapSqlParameterSource parameters = new MapSqlParameterSource()
				.addValue("evaluatedAt", criteria.evaluatedAt())
				.addValue("recentUsageStartAt", criteria.recentUsageStartAt())
				.addValue("lowGapThreshold", criteria.lowGapThreshold())
				.addValue("topK", criteria.topK())
				.addValue("lowSimilarityThreshold", criteria.lowSimilarityThreshold())
				.addValue("lowGapRateThreshold", criteria.lowGapRateThreshold())
				.addValue("minimumTopKSampleCount", criteria.minimumTopKSampleCount())
				.addValue("minimumTop1SampleCount", criteria.minimumTop1SampleCount())
				.addValue("filterLowRagQuality", criteria.types() != null && criteria.types().contains(FaqImprovementType.LOW_RAG_QUALITY))
				.addValue("filterDuplicateSuspected", criteria.types() != null && criteria.types().contains(FaqImprovementType.DUPLICATE_SUSPECTED))
				.addValue("filterLowUsage", criteria.types() != null && criteria.types().contains(FaqImprovementType.LOW_USAGE))
				.addValue("size", criteria.size())
				.addValue("offset", (long) criteria.page() * criteria.size());
		return jdbcTemplate.query(sql, parameters, rs -> {
			List<FaqImprovementItem> items = new ArrayList<>();
			long totalElements = 0;
			while (rs.next()) {
				totalElements = rs.getLong("total_count");
				Long faqId = rs.getObject("faq_id", Long.class);
				if (faqId == null) {
					continue;
				}
				List<FaqImprovementType> improvementTypes = new ArrayList<>(3);
				if (rs.getBoolean("low_rag_quality")) {
					improvementTypes.add(FaqImprovementType.LOW_RAG_QUALITY);
				}
				if (rs.getBoolean("duplicate_suspected")) {
					improvementTypes.add(FaqImprovementType.DUPLICATE_SUSPECTED);
				}
				if (rs.getBoolean("low_usage")) {
					improvementTypes.add(FaqImprovementType.LOW_USAGE);
				}
				items.add(new FaqImprovementItem(
						faqId, rs.getString("question"), Intent.valueOf(rs.getString("intent")),
						rs.getInt("faq_version"), rs.getTimestamp("version_started_at").toLocalDateTime(),
						improvementTypes, rs.getLong("top_k_count"), rs.getDouble("average_similarity"),
						rs.getLong("top1_sample_count"), rs.getLong("recent_usage_count"),
						rs.getDouble("average_top_gap"), rs.getDouble("low_gap_rate")
				));
			}
			return PageResponseDto.of(items, criteria.page(), criteria.size(), totalElements);
		});
	}

	public List<FaqDuplicateCandidate> findDuplicateCandidates(
			Long faqId,
			LocalDateTime evaluatedAt,
			double lowGapThreshold
	) {
		String sql = """
				WITH pair_counts AS (
					SELECT other_faq.id AS candidate_faq_id, COUNT(*) AS pair_count
					FROM faq target_faq
					JOIN faq_log target_log
						ON target_log.faq_id = target_faq.id
						AND target_log.rank = 1
					JOIN question_log ql
						ON ql.id = target_log.question_log_id
						AND ql.created_at >= target_faq.updated_at
						AND ql.created_at < :evaluatedAt
					JOIN faq_log other_log
						ON other_log.question_log_id = target_log.question_log_id
						AND other_log.rank = 2
						AND other_log.faq_id <> target_faq.id
					JOIN faq other_faq
						ON other_faq.id = other_log.faq_id
						AND other_faq.deleted_at IS NULL
						AND ql.created_at >= other_faq.updated_at
					WHERE target_faq.id = :faqId
						AND target_faq.deleted_at IS NULL
						AND target_log.similarity - other_log.similarity < :lowGapThreshold
					GROUP BY other_faq.id
				), top1_counts AS (
					SELECT fl.faq_id, COUNT(fl.question_log_id) AS top1_count
					FROM pair_counts pairs
					JOIN faq f ON f.id = pairs.candidate_faq_id
					JOIN faq_log fl ON fl.faq_id = f.id AND fl.rank = 1
					JOIN question_log ql ON ql.id = fl.question_log_id
						AND ql.created_at >= f.updated_at
						AND ql.created_at < :evaluatedAt
					GROUP BY fl.faq_id
				)
				SELECT pairs.candidate_faq_id, f.question,
					COALESCE(counts.top1_count, 0) AS top1_count, pairs.pair_count
				FROM pair_counts pairs
				JOIN faq f ON f.id = pairs.candidate_faq_id AND f.deleted_at IS NULL
				LEFT JOIN top1_counts counts ON counts.faq_id = pairs.candidate_faq_id
				ORDER BY pairs.pair_count DESC, pairs.candidate_faq_id ASC
				""";

		MapSqlParameterSource parameters = new MapSqlParameterSource()
				.addValue("faqId", faqId)
				.addValue("evaluatedAt", evaluatedAt)
				.addValue("lowGapThreshold", lowGapThreshold);
		return jdbcTemplate.query(sql, parameters, (rs, rowNum) -> new FaqDuplicateCandidate(
				rs.getLong("candidate_faq_id"), rs.getString("question"),
				rs.getLong("top1_count"), rs.getLong("pair_count")
		));
	}
}
