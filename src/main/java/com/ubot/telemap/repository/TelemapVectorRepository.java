package com.ubot.telemap.repository;

import java.time.LocalDateTime;
import java.util.Optional;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import com.pgvector.PGvector;
import com.ubot.ranking.enums.RegionSido;

import lombok.RequiredArgsConstructor;

@Repository
@RequiredArgsConstructor
public class TelemapVectorRepository {

    private final JdbcTemplate jdbcTemplate;

    public Optional<Long> findNearestClusterId(
            RegionSido region,
            long embeddingProfileId,
            PGvector vector,
            double threshold,
            LocalDateTime activeSince
    ) {
        return jdbcTemplate.query(
                """
                SELECT id
                FROM surging_clusters
                WHERE region = ?
                  AND embedding_profile_id = ?
                  AND last_occurred_at >= ?
                  AND 1 - (centroid_embedding <=> ?) >= ?
                ORDER BY centroid_embedding <=> ?, id
                LIMIT 1
                """,
                (rs, rowNum) -> rs.getLong("id"),
                region.name(), embeddingProfileId, activeSince, vector, threshold, vector
        ).stream().findFirst();
    }

    public long createCluster(
            RegionSido region,
            String representativeQuestion,
            long embeddingProfileId,
            PGvector vector,
            LocalDateTime occurredAt
    ) {
        return jdbcTemplate.queryForObject(
                """
                INSERT INTO surging_clusters (
                    region, representative_question, embedding_profile_id,
                    centroid_embedding, last_occurred_at
                )
                VALUES (?, ?, ?, ?, ?)
                RETURNING id
                """,
                Long.class,
                region.name(), representativeQuestion, embeddingProfileId, vector, occurredAt
        );
    }

    public void saveQuestionEmbedding(long questionId, long embeddingProfileId, PGvector vector) {
        jdbcTemplate.update(
                """
                INSERT INTO surging_question_embeddings (question_id, embedding_profile_id, vector)
                VALUES (?, ?, ?)
                """,
                questionId, embeddingProfileId, vector
        );
    }

    public void updateClusterCentroid(long clusterId, long embeddingProfileId, LocalDateTime occurredAt) {
        jdbcTemplate.update(
                """
                UPDATE surging_clusters c
                SET centroid_embedding = (
                        SELECT AVG(sqe.vector)
                        FROM surging_question_embeddings sqe
                        JOIN surging_questions sq ON sq.id = sqe.question_id
                        WHERE sq.cluster_id = c.id
                          AND sqe.embedding_profile_id = ?
                    ),
                    last_occurred_at = GREATEST(c.last_occurred_at, ?),
                    updated_at = CURRENT_TIMESTAMP
                WHERE c.id = ?
                """,
                embeddingProfileId, occurredAt, clusterId
        );
    }
}
