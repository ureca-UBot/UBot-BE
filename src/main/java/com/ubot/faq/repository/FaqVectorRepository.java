package com.ubot.faq.repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;
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
                        f.version AS faq_version,
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
                SELECT faq_id, faq_question, answer, intent, cacheable, faq_version,
                       variant_id, variant_question, similarity_score
                FROM ranked
                WHERE faq_rank = 1
                ORDER BY similarity_score DESC, faq_id ASC
                LIMIT ?
                """.formatted(intentFilter);

        Object[] params = intent == null
                ? new Object[] {queryEmbedding, profileId, vectorType.name(), topK}
                : new Object[] {queryEmbedding, profileId, vectorType.name(), intent.name(), topK};
        return jdbcTemplate.query(sql, this::mapSearchResult, params);
    }

    /**
     * 검색 당시와 질문·답변·의도·유사표현이 같은 활성 후보만 반환합니다.
     * 승인 여부만 바뀐 FAQ도 RAG에서는 사용할 수 있으므로 cacheable·version은 현재 값을 읽습니다.
     */
    public List<FaqSearchResponseDto> findUnchangedCandidates(List<FaqSearchResponseDto> candidates) {
        if (candidates.isEmpty()) {
            return List.of();
        }
        String rows = String.join(", ", Collections.nCopies(candidates.size(),
                "(?::bigint, ?::bigint, ?::text, ?::text, ?::text, ?::text, ?::double precision)"));
        List<Object> parameters = new ArrayList<>();
        for (FaqSearchResponseDto candidate : candidates) {
            Collections.addAll(parameters, candidate.faqId(), candidate.variantId(), candidate.question(),
                    candidate.answer(), candidate.intent().name(), candidate.variantQuestion(), candidate.similarityScore());
        }
        return jdbcTemplate.query("""
                WITH snapshots(faq_id, variant_id, question, answer, intent, variant_question, similarity_score) AS (
                    VALUES %s
                )
                SELECT f.id AS faq_id, f.question AS faq_question, f.answer, f.intent,
                       f.cacheable, f.version AS faq_version, v.id AS variant_id,
                       v.question AS variant_question, s.similarity_score
                FROM snapshots s
                JOIN faq f ON f.id = s.faq_id
                JOIN faq_question_variants v ON v.id = s.variant_id AND v.origin_faq_id = f.id
                WHERE f.deleted_at IS NULL
                  AND f.question = s.question AND f.answer = s.answer
                  AND f.intent::text = s.intent AND v.question = s.variant_question
                ORDER BY s.similarity_score DESC, f.id ASC
                """.formatted(rows), this::mapSearchResult, parameters.toArray());
    }

    private FaqSearchResponseDto mapSearchResult(ResultSet rs, int rowNum) throws SQLException {
        return new FaqSearchResponseDto(
                rs.getLong("faq_id"),
                rs.getString("faq_question"),
                rs.getString("answer"),
                rs.getDouble("similarity_score"),
                Intent.valueOf(rs.getString("intent")),
                rs.getLong("variant_id"),
                rs.getString("variant_question"),
                rs.getBoolean("cacheable"),
                rs.getInt("faq_version")
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
