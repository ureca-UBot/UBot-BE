package com.ubot.unanswered.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import com.pgvector.PGvector;
import com.ubot.unanswered.enums.UnansweredReason;
import com.ubot.unanswered.enums.UnansweredSource;

import lombok.RequiredArgsConstructor;

@Repository
@RequiredArgsConstructor
public class UnansweredQuestionVectorRepository {

    private final JdbcTemplate jdbcTemplate;

    /** 기존 질문과 문장·프로필이 정확히 같은 벡터만 재사용합니다. 다른 질문의 벡터를 유사하다는 이유로 빌리지 않습니다. */
    public Optional<PGvector> findQuestionEmbedding(String question, Long profileId) {
        return jdbcTemplate.query(
                """
                SELECT uqe.vector::text AS vector
                FROM unanswered_questions uq
                JOIN unanswered_question_embeddings uqe
                  ON uqe.question_id = uq.id
                WHERE uq.sub_question = ?
                  AND uqe.profile_id = ?
                ORDER BY uq.created_at DESC, uq.id DESC
                LIMIT 1
                """,
                (rs, rowNum) -> new PGvector(rs.getString("vector")),
                question,
                profileId
        ).stream().findFirst();
    }

    /**
     * 반려 묶음은 대기·보류 묶기와 별도로 검색합니다. 가장 가까운 묶음의 요구가 다를 수 있어 여러 후보를 반환합니다.
     * 여기서는 유사도만 확인하며 실제 재등록 생략은 호출한 쪽에서 대상·조건·요구를 추가 확인한 뒤 결정합니다.
     */
    public List<RejectedGroup> findRejectedGroups(PGvector vector, Long profileId, double rejectedThreshold, int limit) {
        return jdbcTemplate.query(
                """
                SELECT g.id, g.representative_question, 1 - (uge.centroid <=> ?) AS similarity
                FROM unanswered_question_groups g
                JOIN unanswered_group_embeddings uge
                  ON uge.group_id = g.id
                WHERE g.status = 'REJECTED'
                  AND uge.profile_id = ?
                  AND 1 - (uge.centroid <=> ?) >= ?
                ORDER BY uge.centroid <=> ?, g.id
                LIMIT ?
                """,
                (rs, rowNum) -> new RejectedGroup(rs.getLong("id"), rs.getString("representative_question"),
                        rs.getDouble("similarity")),
                vector,
                profileId,
                vector,
                rejectedThreshold,
                vector,
                limit
        );
    }

    public record RejectedGroup(Long groupId, String question, double similarity) {
    }

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
                    related_faq_id
                )
                VALUES (?, ?)
                RETURNING id
                """,
                Long.class,
                question,
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

    public Long saveQuestion(
            Long attemptId,
            int subQuestionIndex,
            Long groupId,
            String subQuestion,
            String userText,
            PGvector vector,
            Long profileId,
            UnansweredReason reason,
            Long bestFaqId,
            Double bestSimilarity,
            UnansweredSource source
    ) {
        Long questionId = jdbcTemplate.queryForObject(
                """
                INSERT INTO unanswered_questions (
                    attempt_id,
                    sub_question_index,
                    group_id,
                    sub_question,
                    user_text,
                    reason,
                    best_faq_id,
                    best_similarity,
                    source
                )
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                RETURNING id
                """,
                Long.class,
                attemptId,
                subQuestionIndex,
                groupId,
                subQuestion,
                userText,
                reason.name(),
                bestFaqId,
                bestSimilarity,
                source.name()
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
        return questionId;
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
                SET question_count = (
                        SELECT COUNT(*)
                        FROM unanswered_questions uq
                        WHERE uq.group_id = g.id
                    ),
                    last_occurred_at = CURRENT_TIMESTAMP,
                    updated_at = CURRENT_TIMESTAMP
                WHERE g.id = ?
                """,
                groupId
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
