package com.ubot.faq.repository;

import com.pgvector.PGvector;
import com.ubot.faq.dto.response.FaqCacheQuestionResponseDto;
import com.ubot.faq.enums.Intent;
import com.ubot.faq.policy.FaqQuestionNormalizer;
import jakarta.persistence.EntityManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
public class FaqCacheQuestionRepository {
    private static final String FAQ_SNAPSHOT_SQL = """
            SELECT f.id, f.question, f.answer, f.version, f.intent, f.cacheable,
                   EXISTS(SELECT 1 FROM faq_embeddings fe WHERE fe.faq_id = f.id
                     AND fe.profile_id = ? AND fe.vector_type = 'QUESTION'
                     AND fe.faq_version = f.version) AS current_vector
            FROM faq f WHERE f.id = ? AND f.deleted_at IS NULL
            """;

    private final JdbcTemplate jdbcTemplate;
    private final EntityManager entityManager;

    public Optional<FaqSnapshot> getActiveFaq(Long faqId, Long profileId) {
        return getFaqSnapshot(FAQ_SNAPSHOT_SQL, faqId, profileId);
    }

    public Optional<FaqSnapshot> getActiveFaqForUpdate(Long faqId, Long profileId) {
        return getFaqSnapshot(FAQ_SNAPSHOT_SQL + " FOR UPDATE OF f", faqId, profileId);
    }

    public boolean lockActiveFaq(Long faqId) {
        return !jdbcTemplate.query("""
                SELECT id FROM faq WHERE id = ? AND deleted_at IS NULL FOR UPDATE
                """, (rs, rowNum) -> rs.getLong("id"), faqId).isEmpty();
    }

    public List<FaqCacheQuestionResponseDto> getQuestionList(Long faqId, Long currentProfileId) {
        return jdbcTemplate.query("""
                SELECT c.id, c.faq_id, c.profile_id, c.question, c.enabled, c.faq_version,
                       f.version AS current_faq_version, c.created_at, c.normalized_question, c.normalization_version,
                       (c.enabled AND c.profile_id = ? AND f.deleted_at IS NULL
                        AND c.normalization_version = ? AND f.cacheable AND f.intent = 'GENERAL'
                        AND c.faq_version = f.version AND c.faq_question = f.question
                        AND c.faq_answer = f.answer
                        AND EXISTS(SELECT 1 FROM faq_embeddings fe WHERE fe.faq_id = f.id
                          AND fe.profile_id = c.profile_id AND fe.vector_type = 'QUESTION'
                          AND fe.faq_version = f.version)) AS valid
                FROM faq_cache_question c JOIN faq f ON f.id = c.faq_id
                WHERE c.faq_id = ? ORDER BY c.id
                """, (rs, rowNum) -> toResponse(rs), currentProfileId, FaqQuestionNormalizer.VERSION, faqId);
    }

    public FaqCacheQuestionResponseDto upsertQuestion(
            FaqSnapshot faq, Long profileId, String question, PGvector vector) {
        return jdbcTemplate.queryForObject("""
                INSERT INTO faq_cache_question
                    (faq_id, profile_id, question, vector, faq_version, faq_question, faq_answer, enabled, normalization_version)
                VALUES (?, ?, ?, ?, ?, ?, ?, true, ?)
                ON CONFLICT (faq_id, normalized_question, profile_id) DO UPDATE SET
                    question = EXCLUDED.question, normalization_version = EXCLUDED.normalization_version,
                    vector = EXCLUDED.vector, faq_version = EXCLUDED.faq_version,
                    faq_question = EXCLUDED.faq_question, faq_answer = EXCLUDED.faq_answer, enabled = true
                RETURNING id, faq_id, profile_id, question, enabled, faq_version,
                          faq_version AS current_faq_version, created_at, normalized_question, normalization_version, true AS valid
                """, (rs, rowNum) -> toResponse(rs), faq.faqId(), profileId, question, vector,
                faq.version(), faq.question(), faq.answer(), FaqQuestionNormalizer.VERSION);
    }

    public int deactivateQuestion(Long faqId, Long questionId) {
        return entityManager.createQuery("""
                UPDATE FaqCacheQuestion c SET c.enabled = false WHERE c.faq.id = :faqId AND c.id = :questionId
                """).setParameter("faqId", faqId).setParameter("questionId", questionId).executeUpdate();
    }

    private Optional<FaqSnapshot> getFaqSnapshot(String sql, Long faqId, Long profileId) {
        return jdbcTemplate.query(sql, (rs, rowNum) -> new FaqSnapshot(
                rs.getLong("id"), rs.getString("question"), rs.getString("answer"),
                rs.getInt("version"), Intent.valueOf(rs.getString("intent")),
                rs.getBoolean("cacheable"), rs.getBoolean("current_vector")), profileId, faqId)
                .stream().findFirst();
    }

    private FaqCacheQuestionResponseDto toResponse(ResultSet rs) throws SQLException {
        return new FaqCacheQuestionResponseDto(rs.getLong("id"), rs.getLong("faq_id"),
                rs.getLong("profile_id"), rs.getString("question"), rs.getBoolean("enabled"),
                rs.getBoolean("valid"), rs.getInt("faq_version"), rs.getInt("current_faq_version"),
                rs.getTimestamp("created_at").toLocalDateTime(), rs.getString("normalized_question"),
                rs.getInt("normalization_version"));
    }

    public record FaqSnapshot(Long faqId, String question, String answer, int version,
                              Intent intent, boolean cacheable, boolean currentVector) {
    }
}
