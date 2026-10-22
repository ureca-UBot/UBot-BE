package com.ubot.unanswered.repository;

import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import com.ubot.faq.repository.FaqVectorRepository;
import com.ubot.unanswered.dto.response.UnansweredReverificationResponseDto;

import lombok.RequiredArgsConstructor;

@Repository
@RequiredArgsConstructor
public class UnansweredReverifyRepository {

    private final NamedParameterJdbcTemplate jdbcTemplate;

    public int reverify(long faqId, double threshold) {
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("faqId", faqId)
                .addValue("threshold", threshold);
        jdbcTemplate.update("DELETE FROM unanswered_question_reverifications WHERE faq_id = :faqId", params);
        return jdbcTemplate.update(
                """
                INSERT INTO unanswered_question_reverifications (question_id, faq_id, before_similarity, after_similarity)
                SELECT q.id, :faqId, q.best_similarity, matched.similarity
                FROM unanswered_questions q
                JOIN unanswered_question_groups g ON g.id = q.group_id
                JOIN unanswered_question_embeddings qe ON qe.question_id = q.id
                CROSS JOIN LATERAL (
                    SELECT max(1 - (fe.vector <=> qe.vector)) AS similarity
                    FROM faq_embeddings fe
                    JOIN faq_question_variants v ON v.id = fe.faq_question_variant_id
                    JOIN faq f ON f.id = v.origin_faq_id
                    WHERE f.id = :faqId
                      AND f.deleted_at IS NULL
                      AND fe.profile_id = qe.profile_id
                      AND fe.vector_type = 'QUESTION'
                      AND fe.faq_version = f.version
                ) matched
                WHERE g.status IN ('PENDING', 'ON_HOLD')
                  AND matched.similarity >= :threshold
                  AND (q.best_similarity IS NULL OR matched.similarity > q.best_similarity)
                ON CONFLICT (question_id, faq_id) DO NOTHING
                """,
                params
        );
    }

    public void reverifyQuestion(Long questionId, double threshold) {
        jdbcTemplate.getJdbcTemplate().execute(FaqVectorRepository.HNSW_SEARCH_SETTINGS_SQL);
        jdbcTemplate.update(
                """
                INSERT INTO unanswered_question_reverifications (question_id, faq_id, before_similarity, after_similarity)
                SELECT q.id, best.faq_id, q.best_similarity, best.similarity
                FROM unanswered_questions q
                JOIN unanswered_question_embeddings qe ON qe.question_id = q.id
                CROSS JOIN LATERAL (
                    SELECT f.id AS faq_id, 1 - (fe.vector <=> qe.vector) AS similarity
                    FROM faq_embeddings fe
                    JOIN faq_question_variants v ON v.id = fe.faq_question_variant_id
                    JOIN faq f ON f.id = v.origin_faq_id
                    WHERE f.deleted_at IS NULL
                      AND fe.profile_id = qe.profile_id
                      AND fe.vector_type = 'QUESTION'
                      AND fe.faq_version = f.version
                    ORDER BY fe.vector <=> qe.vector
                    LIMIT 1
                ) best
                WHERE q.id = :questionId
                  AND best.similarity >= :threshold
                  AND (q.best_similarity IS NULL OR best.similarity > q.best_similarity)
                ON CONFLICT (question_id, faq_id) DO NOTHING
                """,
                new MapSqlParameterSource()
                        .addValue("questionId", questionId)
                        .addValue("threshold", threshold)
        );
    }

    public Map<Long, ReverificationSummary> findSummaries(Collection<Long> groupIds) {
        Map<Long, ReverificationSummary> summaries = new HashMap<>();
        if (groupIds.isEmpty()) {
            return summaries;
        }
        jdbcTemplate.query(
                """
                SELECT DISTINCT ON (q.group_id) q.group_id, r.faq_id, count(*) AS question_count
                FROM unanswered_question_reverifications r
                JOIN unanswered_questions q ON q.id = r.question_id
                JOIN unanswered_question_groups g ON g.id = q.group_id
                WHERE q.group_id IN (:groupIds)
                  AND g.status IN ('PENDING', 'ON_HOLD')
                GROUP BY q.group_id, r.faq_id
                ORDER BY q.group_id, count(*) DESC, max(r.after_similarity) DESC, r.faq_id
                """,
                new MapSqlParameterSource("groupIds", groupIds),
                rs -> {
                    summaries.put(rs.getLong("group_id"),
                            new ReverificationSummary(rs.getLong("faq_id"), rs.getInt("question_count")));
                }
        );
        return summaries;
    }

    public List<UnansweredReverificationResponseDto> findByGroupId(long groupId) {
        return jdbcTemplate.query(
                """
                SELECT r.question_id, r.faq_id, f.question AS faq_question, r.before_similarity, r.after_similarity
                FROM unanswered_question_reverifications r
                JOIN unanswered_questions q ON q.id = r.question_id
                JOIN unanswered_question_groups g ON g.id = q.group_id
                JOIN faq f ON f.id = r.faq_id
                WHERE q.group_id = :groupId
                  AND g.status IN ('PENDING', 'ON_HOLD')
                ORDER BY r.after_similarity DESC, r.question_id, r.faq_id
                """,
                new MapSqlParameterSource("groupId", groupId),
                (rs, rowNum) -> new UnansweredReverificationResponseDto(
                        rs.getLong("question_id"),
                        rs.getLong("faq_id"),
                        rs.getString("faq_question"),
                        rs.getObject("before_similarity", Double.class),
                        rs.getDouble("after_similarity")
                )
        );
    }

    public record ReverificationSummary(long faqId, int questionCount) {
    }
}
