package com.ubot.faq.repository;

import java.util.List;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import com.pgvector.PGvector;
import com.ubot.faq.dto.response.FaqSearchResponseDto;
import com.ubot.faq.dto.response.SemanticFaqCandidateResponseDto;
import com.ubot.faq.enums.Intent;

import lombok.RequiredArgsConstructor;

@Repository
@RequiredArgsConstructor
public class FaqVectorRepository {

    private static final String QUESTION_VECTOR_TYPE = "QUESTION";

    private final JdbcTemplate jdbcTemplate;

    /** Group by FAQ before calculating the margin; retain unapproved competitors. */
    public List<SemanticFaqCandidateResponseDto> getSemanticCandidates(PGvector queryEmbedding, Long profileId) {
        String sql = """
                WITH active AS (
                    SELECT f.id, f.question, f.answer, f.intent, f.cacheable, f.version, fe.vector
                    FROM faq f JOIN faq_embeddings fe ON fe.faq_id = f.id
                    WHERE f.deleted_at IS NULL AND fe.profile_id = ?
                      AND fe.vector_type = 'QUESTION' AND fe.faq_version = f.version
                ), valid AS (
                    SELECT c.faq_id, c.question, c.vector FROM faq_cache_question c
                    JOIN active f ON f.id = c.faq_id
                    WHERE c.enabled AND c.profile_id = ? AND f.cacheable AND f.intent = 'GENERAL'
                      AND c.faq_version = f.version AND c.faq_question = f.question AND c.faq_answer = f.answer
                ), expressions AS (
                    SELECT id AS faq_id, question, vector FROM active
                    UNION ALL SELECT faq_id, question, vector FROM valid
                ), best AS (
                    SELECT DISTINCT ON (faq_id) faq_id, question AS matched_question,
                           1 - (vector <=> ?) AS similarity_score
                    FROM expressions ORDER BY faq_id, similarity_score DESC, question
                )
                SELECT f.id, f.question, f.answer, f.intent, f.cacheable, f.version,
                       b.matched_question, b.similarity_score,
                       EXISTS(SELECT 1 FROM valid v WHERE v.faq_id = f.id) AS registered
                FROM best b JOIN active f ON f.id = b.faq_id
                ORDER BY b.similarity_score DESC, f.id LIMIT 20
                """;
        return jdbcTemplate.query(sql, (rs, rowNum) -> new SemanticFaqCandidateResponseDto(
                new FaqSearchResponseDto(rs.getLong("id"), rs.getString("question"), rs.getString("answer"),
                        rs.getDouble("similarity_score"), Intent.valueOf(rs.getString("intent")),
                        rs.getBoolean("cacheable"), rs.getInt("version")),
                rs.getString("matched_question"), rs.getBoolean("registered")), profileId, profileId, queryEmbedding);
    }

    public List<FaqSearchResponseDto> getSimilarList(
            PGvector queryEmbedding,
            Long profileId,
            int topK
    ) {
        String sql = """
                SELECT
                    f.id,
                    f.question,
                    f.answer,
                    f.intent,
                    f.cacheable,
                    f.version,
                    1 - (fe.vector <=> ?) AS similarity_score
                FROM faq f
                JOIN faq_embeddings fe
                  ON fe.faq_id = f.id
                WHERE f.deleted_at IS NULL
                  AND fe.profile_id = ?
                  AND fe.vector_type = 'QUESTION'
                  AND fe.faq_version = f.version
                ORDER BY fe.vector <=> ?
                LIMIT ?
                """;

        return jdbcTemplate.query(
                sql,
                (rs, rowNum) -> new FaqSearchResponseDto(
                        rs.getLong("id"),
                        rs.getString("question"),
                        rs.getString("answer"),
                        rs.getDouble("similarity_score"),
                        Intent.valueOf(rs.getString("intent")),
                        rs.getBoolean("cacheable"), rs.getInt("version")
                ),
                queryEmbedding,
                profileId,
                queryEmbedding,
                topK
        );
    }

    public List<FaqSearchResponseDto> getSimilarListByIntent(
            PGvector queryEmbedding,
            Long profileId,
            Intent intent,
            int topK
    ) {
        String sql = """
                SELECT
                    f.id,
                    f.question,
                    f.answer,
                    f.intent,
                    f.cacheable,
                    f.version,
                    1 - (fe.vector <=> ?) AS similarity_score
                FROM faq f
                JOIN faq_embeddings fe
                  ON fe.faq_id = f.id
                WHERE f.deleted_at IS NULL
                  AND f.intent = ?
                  AND fe.profile_id = ?
                  AND fe.vector_type = 'QUESTION'
                  AND fe.faq_version = f.version
                ORDER BY fe.vector <=> ?
                LIMIT ?
                """;

        return jdbcTemplate.query(
                sql,
                (rs, rowNum) -> new FaqSearchResponseDto(
                        rs.getLong("id"),
                        rs.getString("question"),
                        rs.getString("answer"),
                        rs.getDouble("similarity_score"),
                        Intent.valueOf(rs.getString("intent")),
                        rs.getBoolean("cacheable"), rs.getInt("version")
                ),
                queryEmbedding,
                intent.name(),
                profileId,
                queryEmbedding,
                topK
        );
    }

    public void saveVectorForFaq(
            Long faqId,
            Long profileId,
            Integer faqVersion,
            PGvector embedding
    ) {
        jdbcTemplate.update(
                """
                INSERT INTO faq_embeddings (
                    faq_id,
                    profile_id,
                    vector_type,
                    faq_version,
                    vector
                )
                VALUES (?, ?, ?, ?, ?)
                ON CONFLICT (faq_id, profile_id, vector_type)
                DO UPDATE SET
                    faq_version = EXCLUDED.faq_version,
                    vector = EXCLUDED.vector,
                    updated_at = CURRENT_TIMESTAMP
                """,
                faqId,
                profileId,
                QUESTION_VECTOR_TYPE,
                faqVersion,
                embedding
        );
    }

    public void updateVectorVersionForFaq(
            Long faqId,
            Long profileId,
            Integer faqVersion
    ) {
        jdbcTemplate.update(
                """
                UPDATE faq_embeddings
                SET faq_version = ?,
                    updated_at = CURRENT_TIMESTAMP
                WHERE faq_id = ?
                  AND profile_id = ?
                  AND vector_type = ?
                """,
                faqVersion,
                faqId,
                profileId,
                QUESTION_VECTOR_TYPE
        );
    }
    public boolean hasCurrentVector(
            Long faqId,
            Long profileId,
            Integer faqVersion
    ) {
        Integer count = jdbcTemplate.queryForObject(
                """
                SELECT COUNT(*)
                FROM faq_embeddings
                WHERE faq_id = ?
                  AND profile_id = ?
                  AND vector_type = 'QUESTION'
                  AND faq_version = ?
                """,
                Integer.class,
                faqId,
                profileId,
                faqVersion
        );

        return count != null && count > 0;
    }
}
