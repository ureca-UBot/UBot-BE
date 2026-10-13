package com.ubot.faq.repository;

import java.util.List;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import com.pgvector.PGvector;
import com.ubot.faq.dto.response.FaqSearchResponseDto;
import com.ubot.faq.dto.response.SemanticFaqCandidateResponseDto;
import com.ubot.faq.enums.FaqVectorType;
import com.ubot.faq.enums.Intent;
import com.ubot.faq.policy.FaqQuestionNormalizer;

import lombok.RequiredArgsConstructor;

@Repository
@RequiredArgsConstructor
public class FaqVectorRepository {

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
                      AND c.normalization_version = ?
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
                rs.getString("matched_question"), rs.getBoolean("registered")), profileId, profileId,
                FaqQuestionNormalizer.VERSION, queryEmbedding);
    }

    // 두 검색 쿼리는 거리 연산자(<=>)가 아니라 유사도로 정렬합니다. 그래야 HNSW 인덱스를 타지 않고 전체를 비교합니다.
    // HNSW는 가까운 후보를 먼저 고른 뒤에 프로필·유형 조건을 적용해서, 조건에 맞는 벡터를 놓치거나 topK보다 적게 돌려줍니다.
    public List<FaqSearchResponseDto> getSimilarList(
            PGvector queryEmbedding,
            Long profileId,
            FaqVectorType vectorType,
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
                  AND fe.vector_type = ?
                  AND fe.faq_version = f.version
                ORDER BY similarity_score DESC
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
                vectorType.name(),
                topK
        );
    }

    public List<FaqSearchResponseDto> getSimilarListByIntent(
            PGvector queryEmbedding,
            Long profileId,
            FaqVectorType vectorType,
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
                  AND fe.vector_type = ?
                  AND fe.faq_version = f.version
                ORDER BY similarity_score DESC
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
                vectorType.name(),
                topK
        );
    }

    public void saveVectorForFaq(
            Long faqId,
            Long profileId,
            FaqVectorType vectorType,
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
                vectorType.name(),
                faqVersion,
                embedding
        );
    }

    public void updateVectorVersionForFaq(
            Long faqId,
            Long profileId,
            FaqVectorType vectorType,
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
                vectorType.name()
        );
    }
    public boolean hasCurrentVector(
            Long faqId,
            Long profileId,
            FaqVectorType vectorType,
            Integer faqVersion
    ) {
        Integer count = jdbcTemplate.queryForObject(
                """
                SELECT COUNT(*)
                FROM faq_embeddings
                WHERE faq_id = ?
                  AND profile_id = ?
                  AND vector_type = ?
                  AND faq_version = ?
                """,
                Integer.class,
                faqId,
                profileId,
                vectorType.name(),
                faqVersion
        );

        return count != null && count > 0;
    }
}
