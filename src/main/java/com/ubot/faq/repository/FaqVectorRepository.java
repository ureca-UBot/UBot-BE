package com.ubot.faq.repository;

import java.util.List;

import org.postgresql.util.PGobject;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import com.pgvector.PGvector;
import com.ubot.faq.dto.response.FaqSearchResponseDto;
import com.ubot.faq.enums.Intent;

import lombok.RequiredArgsConstructor;

@Repository
@RequiredArgsConstructor
public class FaqVectorRepository {

    private static final String QUESTION_VECTOR_TYPE = "QUESTION";

    private final JdbcTemplate jdbcTemplate;

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
                        Intent.valueOf(rs.getString("intent"))
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
                        Intent.valueOf(rs.getString("intent"))
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

    public PGvector findVectorByFaqId(
            Long faqId,
            Long profileId
    ) {
        List<PGvector> vectors = jdbcTemplate.query(
                """
                SELECT fe.vector
                FROM faq_embeddings fe
                JOIN faq f
                  ON f.id = fe.faq_id
                WHERE fe.faq_id = ?
                  AND fe.profile_id = ?
                  AND fe.vector_type = 'QUESTION'
                  AND fe.faq_version = f.version
                """,
                (rs, rowNum) -> {
                    PGobject pgObject = (PGobject) rs.getObject("vector");

                    if (pgObject == null) {
                        return null;
                    }

                    return new PGvector(pgObject.getValue());
                },
                faqId,
                profileId
        );

        return vectors.isEmpty() ? null : vectors.getFirst();
    }

    public void saveVectorForOldFaq(
            Long faqId,
            Integer version,
            PGvector vector
    ) {
        jdbcTemplate.update(
                """
                UPDATE old_faq
                SET vector = ?
                WHERE faq_id = ?
                  AND version = ?
                """,
                vector,
                faqId,
                version
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