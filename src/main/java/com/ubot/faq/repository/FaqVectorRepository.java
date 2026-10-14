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
        String sql = """
                SELECT
                    f.id,
                    f.question,
                    f.answer,
                    f.intent,
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
                        Intent.valueOf(rs.getString("intent"))
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
                        Intent.valueOf(rs.getString("intent"))
                ),
                queryEmbedding,
                intent.name(),
                profileId,
                vectorType.name(),
                topK
        );
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

    public void deleteVectorsForVariant(Long variantId) {
        jdbcTemplate.update("DELETE FROM faq_embeddings WHERE faq_question_variant_id = ?", variantId);
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
