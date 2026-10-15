package com.ubot.faq.repository;

import java.util.List;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import com.pgvector.PGvector;
import com.ubot.faq.dto.response.FaqSearchResponseDto;
import com.ubot.faq.enums.FaqVectorType;
import com.ubot.faq.enums.Intent;

import lombok.RequiredArgsConstructor;

@Repository
@RequiredArgsConstructor
public class FaqVectorRepository {

    private final JdbcTemplate jdbcTemplate;

    // 두 검색 쿼리는 거리 연산자(<=>)가 아니라 유사도로 정렬합니다. 그래야 HNSW 인덱스를 타지 않고 전체를 비교합니다.
    // HNSW는 가까운 후보를 먼저 고른 뒤에 프로필·유형 조건을 적용해서, 조건에 맞는 벡터를 놓치거나 topK보다 적게 돌려줍니다.
    public List<FaqSearchResponseDto> getSimilarList(
            PGvector queryEmbedding,
            Long profileId,
            FaqVectorType vectorType,
            int topK
    ) {
        return search(queryEmbedding, profileId, vectorType, null, topK);
    }

    public List<FaqSearchResponseDto> getSimilarListByIntent(
            PGvector queryEmbedding,
            Long profileId,
            FaqVectorType vectorType,
            Intent intent,
            int topK
    ) {
        return search(queryEmbedding, profileId, vectorType, intent, topK);
    }

    private List<FaqSearchResponseDto> search(
            PGvector queryEmbedding, Long profileId, FaqVectorType vectorType, Intent intent, int topK
    ) {
        String intentFilter = intent == null ? "" : "AND f.intent = ?";
        String sql = """
                WITH scored AS (
                    SELECT
                        f.id AS faq_id,
                        f.question AS faq_question,
                        f.answer,
                        f.intent,
                        f.cacheable,
                        v.id AS variant_id,
                        v.question AS variant_question,
                        1 - (fe.vector <=> ?) AS similarity_score
                    FROM faq_embeddings fe
                    JOIN faq_question_variants v ON v.id = fe.faq_question_variant_id
                    JOIN faq f ON f.id = v.origin_faq_id
                    WHERE f.deleted_at IS NULL
                      AND fe.profile_id = ?
                      AND fe.vector_type = ?
                      AND fe.faq_version = f.version
                      %s
                ), ranked AS (
                    SELECT scored.*,
                           ROW_NUMBER() OVER (
                               PARTITION BY faq_id
                               ORDER BY similarity_score DESC, variant_id ASC
                           ) AS faq_rank
                    FROM scored
                )
                SELECT faq_id, faq_question, answer, intent, cacheable,
                       variant_id, variant_question, similarity_score
                FROM ranked
                WHERE faq_rank = 1
                ORDER BY similarity_score DESC, faq_id ASC
                LIMIT ?
                """.formatted(intentFilter);

        Object[] params = intent == null
                ? new Object[] {queryEmbedding, profileId, vectorType.name(), topK}
                : new Object[] {queryEmbedding, profileId, vectorType.name(), intent.name(), topK};
        return jdbcTemplate.query(sql, (rs, rowNum) -> new FaqSearchResponseDto(
                rs.getLong("faq_id"),
                rs.getString("faq_question"),
                rs.getString("answer"),
                rs.getDouble("similarity_score"),
                Intent.valueOf(rs.getString("intent")),
                rs.getLong("variant_id"),
                rs.getString("variant_question"),
                rs.getBoolean("cacheable")
        ), params);
    }

    public void saveVectorForVariant(
            Long variantId,
            Long profileId,
            FaqVectorType vectorType,
            Integer faqVersion,
            PGvector embedding
    ) {
        jdbcTemplate.update(
                """
                INSERT INTO faq_embeddings (
                    faq_question_variant_id,
                    profile_id,
                    vector_type,
                    faq_version,
                    vector
                )
                VALUES (?, ?, ?, ?, ?)
                ON CONFLICT (faq_question_variant_id, profile_id, vector_type)
                DO UPDATE SET
                    faq_version = EXCLUDED.faq_version,
                    vector = EXCLUDED.vector,
                    updated_at = CURRENT_TIMESTAMP
                """,
                variantId,
                profileId,
                vectorType.name(),
                faqVersion,
                embedding
        );
    }

    public void updateVectorVersionForVariant(
            Long variantId,
            Long profileId,
            Integer faqVersion
    ) {
        jdbcTemplate.update(
                """
                UPDATE faq_embeddings
                SET faq_version = ?,
                    updated_at = CURRENT_TIMESTAMP
                WHERE faq_question_variant_id = ?
                  AND profile_id = ?
                  AND vector_type IN ('QUESTION', 'QUESTION_ANSWER')
                """,
                faqVersion,
                variantId,
                profileId
        );
    }

    public boolean hasCurrentVector(
            Long variantId,
            Long profileId,
            FaqVectorType vectorType,
            Integer faqVersion
    ) {
        Integer count = jdbcTemplate.queryForObject(
                """
                SELECT COUNT(*)
                FROM faq_embeddings
                WHERE faq_question_variant_id = ?
                  AND profile_id = ?
                  AND vector_type = ?
                  AND faq_version = ?
                """,
                Integer.class,
                variantId,
                profileId,
                vectorType.name(),
                faqVersion
        );

        return count != null && count > 0;
    }
}
