package com.ubot.faq.repository;

import java.util.List;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import com.pgvector.PGvector;
import com.ubot.faq.dto.response.FaqSearchResponseDto;
import com.ubot.faq.enums.FaqVectorType;
import com.ubot.faq.enums.Intent;

import lombok.RequiredArgsConstructor;

@Repository
@RequiredArgsConstructor
public class FaqVectorRepository {

    private final JdbcTemplate jdbcTemplate;

    // 필터로 걸러진 후보를 추가 탐색하는 HNSW 설정. is_local=true라 현재 트랜잭션에만 적용됨
    public static final String HNSW_SEARCH_SETTINGS_SQL = """
            SELECT set_config('hnsw.ef_search', '200', true),
                   set_config('hnsw.iterative_scan', 'strict_order', true)
            """;

    static final String SEARCH_SQL = """
            WITH candidates AS MATERIALIZED (
                SELECT f.id AS faq_id, f.question AS faq_question, f.answer, f.intent, f.cacheable,
                       v.id AS variant_id, v.question AS variant_question,
                       fe.vector <=> ? AS distance
                FROM faq_embeddings fe
                JOIN faq_question_variants v ON v.id = fe.faq_question_variant_id
                JOIN faq f ON f.id = v.origin_faq_id
                WHERE f.deleted_at IS NULL
                  AND fe.profile_id = ?
                  AND fe.vector_type = ?
                  AND fe.faq_version = f.version
                  %s
                ORDER BY fe.vector <=> ?
                LIMIT ?
            ), ranked AS (
                SELECT candidates.*, COUNT(*) OVER () AS candidate_count,
                       ROW_NUMBER() OVER (
                           PARTITION BY faq_id ORDER BY distance, variant_id
                       ) AS faq_rank
                FROM candidates
            )
            SELECT faq_id, faq_question, answer, intent, cacheable, variant_id, variant_question,
                   1 - distance AS similarity_score, candidate_count
            FROM ranked
            WHERE faq_rank = 1
            ORDER BY distance, faq_id
            LIMIT ?
            """;

    @Transactional(readOnly = true)
    public List<FaqSearchResponseDto> getSimilarList(
            PGvector queryEmbedding,
            Long profileId,
            FaqVectorType vectorType,
            int topK
    ) {
        return search(queryEmbedding, profileId, vectorType, null, topK);
    }

    @Transactional(readOnly = true)
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
        jdbcTemplate.execute(HNSW_SEARCH_SETTINGS_SQL);
        String sql = SEARCH_SQL.formatted(intent == null ? "" : "AND f.intent = ?");
        int candidateLimit = Math.max(topK, 40);
        while (true) {
            Object[] params = intent == null
                    ? new Object[] {queryEmbedding, profileId, vectorType.name(), queryEmbedding, candidateLimit, topK}
                    : new Object[] {queryEmbedding, profileId, vectorType.name(), intent.name(), queryEmbedding, candidateLimit, topK};
            List<Candidate> candidates = jdbcTemplate.query(sql, (rs, rowNum) -> new Candidate(new FaqSearchResponseDto(
                    rs.getLong("faq_id"),
                    rs.getString("faq_question"),
                    rs.getString("answer"),
                    rs.getDouble("similarity_score"),
                    Intent.valueOf(rs.getString("intent")),
                    rs.getLong("variant_id"),
                    rs.getString("variant_question"),
                    rs.getBoolean("cacheable")
            ), rs.getInt("candidate_count")), params);
            if (candidates.size() >= topK || candidates.isEmpty()
                    || candidates.getFirst().count() < candidateLimit) {
                return candidates.stream().map(Candidate::faq).toList();
            }
            // 같은 FAQ의 여러 표현이 후보를 차지하면 범위를 넓혀 중복 제거 후 topK를 채움
            candidateLimit = Math.multiplyExact(candidateLimit, 2);
        }
    }

    private record Candidate(FaqSearchResponseDto faq, int count) {}

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
                  AND vector_type = 'QUESTION'
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
