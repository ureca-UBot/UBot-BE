package com.ubot.unanswered.repository;

import java.util.Optional;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import com.pgvector.PGvector;
import com.ubot.unanswered.enums.UnansweredReason;

import lombok.RequiredArgsConstructor;

@Repository
@RequiredArgsConstructor
public class UnansweredQuestionVectorRepository {

    private final JdbcTemplate jdbcTemplate;

    public Optional<Long> findNearestGroupId(
            PGvector vector,
            Long profileId,
            double threshold
    ) {
        return jdbcTemplate.query(
                """
                SELECT g.id
                FROM unanswered_question_groups g
                JOIN unanswered_group_embeddings uge
                  ON uge.group_id = g.id
                WHERE g.status IN ('PENDING', 'ON_HOLD')
                  AND uge.profile_id = ?
                  AND 1 - (uge.centroid <=> ?) >= ?
                ORDER BY uge.centroid <=> ?
                LIMIT 1
                """,
                (rs, rowNum) -> rs.getLong("id"),
                profileId,
                vector,
                threshold,
                vector
        ).stream().findFirst();
    }

    public Long saveGroup(
            String question,
            PGvector vector,
            Long profileId,
            Long relatedFaqId
    ) {
        Long groupId = jdbcTemplate.queryForObject(
                """
                INSERT INTO unanswered_question_groups (
                    representative_question,
                    centroid,
                    related_faq_id
                )
                VALUES (?, ?, ?)
                RETURNING id
                """,
                Long.class,
                question,
                vector,
                relatedFaqId
        );

        jdbcTemplate.update(
                """
                INSERT INTO unanswered_group_embeddings (
                    group_id,
                    profile_id,
                    centroid
                )
                VALUES (?, ?, ?)
                """,
                groupId,
                profileId,
                vector
        );

        return groupId;
    }

    public void saveQuestion(
            Long attemptId,
            Long groupId,
            String question,
            PGvector vector,
            Long profileId,
            UnansweredReason reason,
            Long bestFaqId,
            Double bestSimilarity
    ) {
        Long questionId = jdbcTemplate.queryForObject(
                """
                INSERT INTO unanswered_questions (
                    attempt_id,
                    group_id,
                    question,
                    question_vector,
                    reason,
                    best_faq_id,
                    best_similarity
                )
                VALUES (?, ?, ?, ?, ?, ?, ?)
                RETURNING id
                """,
                Long.class,
                attemptId,
                groupId,
                question,
                vector,
                reason.name(),
                bestFaqId,
                bestSimilarity
        );

        jdbcTemplate.update(
                """
                INSERT INTO unanswered_question_embeddings (
                    question_id,
                    profile_id,
                    vector
                )
                VALUES (?, ?, ?)
                """,
                questionId,
                profileId,
                vector
        );
    }

    public void updateGroupCentroid(
            Long groupId,
            Long profileId
    ) {
        jdbcTemplate.update(
                """
                INSERT INTO unanswered_group_embeddings (
                    group_id,
                    profile_id,
                    centroid
                )
                SELECT
                    ?,
                    ?,
                    AVG(uqe.vector)
                FROM unanswered_question_embeddings uqe
                JOIN unanswered_questions uq
                  ON uq.id = uqe.question_id
                WHERE uq.group_id = ?
                  AND uqe.profile_id = ?
                HAVING COUNT(*) > 0
                ON CONFLICT (group_id, profile_id)
                DO UPDATE SET
                    centroid = EXCLUDED.centroid,
                    updated_at = CURRENT_TIMESTAMP
                """,
                groupId,
                profileId,
                groupId,
                profileId
        );

        jdbcTemplate.update(
                """
                UPDATE unanswered_question_groups g
                SET centroid = uge.centroid,
                    question_count = (
                        SELECT COUNT(*)
                        FROM unanswered_questions uq
                        WHERE uq.group_id = g.id
                    ),
                    last_occurred_at = CURRENT_TIMESTAMP,
                    updated_at = CURRENT_TIMESTAMP
                FROM unanswered_group_embeddings uge
                WHERE g.id = ?
                  AND uge.group_id = g.id
                  AND uge.profile_id = ?
                """,
                groupId,
                profileId
        );
    }

    public boolean hasQuestionEmbedding(
            Long questionId,
            Long profileId
    ) {
        Integer count = jdbcTemplate.queryForObject(
                """
                SELECT COUNT(*)
                FROM unanswered_question_embeddings
                WHERE question_id = ?
                  AND profile_id = ?
                """,
                Integer.class,
                questionId,
                profileId
        );

        return count != null && count > 0;
    }

    public void saveQuestionEmbedding(
            Long questionId,
            Long profileId,
            PGvector vector
    ) {
        jdbcTemplate.update(
                """
                INSERT INTO unanswered_question_embeddings (
                    question_id,
                    profile_id,
                    vector
                )
                VALUES (?, ?, ?)
                ON CONFLICT (question_id, profile_id)
                DO UPDATE SET
                    vector = EXCLUDED.vector,
                    updated_at = CURRENT_TIMESTAMP
                """,
                questionId,
                profileId,
                vector
        );
    }

    public void rebuildGroupEmbeddings(
            Long profileId
    ) {
        jdbcTemplate.update(
                """
                INSERT INTO unanswered_group_embeddings (
                    group_id,
                    profile_id,
                    centroid
                )
                SELECT
                    uq.group_id,
                    ?,
                    AVG(uqe.vector)
                FROM unanswered_questions uq
                JOIN unanswered_question_embeddings uqe
                  ON uqe.question_id = uq.id
                 AND uqe.profile_id = ?
                GROUP BY uq.group_id
                ON CONFLICT (group_id, profile_id)
                DO UPDATE SET
                    centroid = EXCLUDED.centroid,
                    updated_at = CURRENT_TIMESTAMP
                """,
                profileId,
                profileId
        );
    }
}
