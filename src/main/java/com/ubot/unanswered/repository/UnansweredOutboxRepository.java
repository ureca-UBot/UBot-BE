package com.ubot.unanswered.repository;

import java.util.Optional;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import com.ubot.unanswered.enums.UnansweredReason;

import lombok.RequiredArgsConstructor;

@Repository
@RequiredArgsConstructor
public class UnansweredOutboxRepository {

    private final JdbcTemplate jdbcTemplate;

    public void enqueue(
            Long attemptId,
            String question,
            UnansweredReason reason,
            Long bestFaqId,
            Double bestSimilarity
    ) {
        jdbcTemplate.update(
                """
                INSERT INTO unanswered_question_outbox (attempt_id, question, reason, best_faq_id, best_similarity)
                VALUES (?, ?, ?, ?, ?)
                ON CONFLICT (attempt_id) DO NOTHING
                """,
                attemptId,
                question,
                reason.name(),
                bestFaqId,
                bestSimilarity
        );
    }

    public Optional<PendingUnanswered> lockNext(int maxRetries) {
        return jdbcTemplate.query(
                """
                SELECT id, attempt_id, question, reason, best_faq_id, best_similarity
                FROM unanswered_question_outbox
                WHERE retry_count < ?
                ORDER BY id
                LIMIT 1
                FOR UPDATE SKIP LOCKED
                """,
                (rs, rowNum) -> new PendingUnanswered(
                        rs.getLong("id"),
                        rs.getLong("attempt_id"),
                        rs.getString("question"),
                        UnansweredReason.valueOf(rs.getString("reason")),
                        rs.getObject("best_faq_id", Long.class),
                        rs.getObject("best_similarity", Double.class)
                ),
                maxRetries
        ).stream().findFirst();
    }

    public void delete(long id) {
        jdbcTemplate.update("DELETE FROM unanswered_question_outbox WHERE id = ?", id);
    }

    public void increaseRetryCount(long id) {
        jdbcTemplate.update("UPDATE unanswered_question_outbox SET retry_count = retry_count + 1 WHERE id = ?", id);
    }

    public record PendingUnanswered(
            long id,
            Long attemptId,
            String question,
            UnansweredReason reason,
            Long bestFaqId,
            Double bestSimilarity
    ) {
    }
}
