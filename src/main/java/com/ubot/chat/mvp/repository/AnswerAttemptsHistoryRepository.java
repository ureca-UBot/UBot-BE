package com.ubot.chat.mvp.repository;

import com.ubot.chat.mvp.domain.ChatAttempt;
import com.ubot.chat.mvp.domain.ChatFailure;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
public class AnswerAttemptsHistoryRepository {
    private final JdbcTemplate jdbc;
    private static final String SELECT = """
            SELECT h.attempt_id, h.question_log_id, q.user_id, q.session_id, h.question, h.attempt_count,
                   h.status, h.idempotency_key, h.error_code, h.error_message,
                   CASE WHEN h.status = 'SUCCESS' THEN q.llm_question ELSE NULL END AS answer
            FROM answer_attempts_history h JOIN question_log q ON q.id = h.question_log_id
            """;
    private static final RowMapper<ChatAttempt> MAPPER = (row, index) -> new ChatAttempt(
            row.getLong("attempt_id"), row.getLong("question_log_id"), row.getLong("user_id"),
            row.getLong("session_id"),
            row.getString("question"), row.getInt("attempt_count"), row.getString("status"),
            row.getString("idempotency_key"), row.getString("answer"),
            row.getString("error_code"), row.getString("error_message"));

    public Optional<ChatAttempt> findById(long attemptId) {
        return jdbc.query(SELECT + " WHERE h.attempt_id = ?", MAPPER, attemptId).stream().findFirst();
    }

    public Optional<ChatAttempt> latest(long questionId) {
        return jdbc.query(SELECT + " WHERE h.question_log_id = ? ORDER BY h.attempt_count DESC LIMIT 1",
                MAPPER, questionId).stream().findFirst();
    }

    public ChatAttempt create(long questionId, String question, int count, String key,
            String llmModel, String embeddingModel) {
        Long id = jdbc.queryForObject("""
                INSERT INTO answer_attempts_history
                  (question_log_id, question, attempt_count, status, idempotency_key, llm_model, embedding_model)
                VALUES (?, ?, ?, 'PENDING', ?, ?, ?) RETURNING attempt_id
                """, Long.class, questionId, question, count, key, llmModel, embeddingModel);
        if (id == null) { throw new IllegalStateException("Attempt ID was not returned"); }
        return findById(id).orElseThrow();
    }

    /** Called under the question row lock, in a short transaction, including after a process restart. */
    public void expirePending(long questionId, long timeoutMillis) {
        ChatFailure failure = ChatFailure.timeout();
        jdbc.update("""
                UPDATE answer_attempts_history SET status = 'FAIL', error_code = ?, error_message = ?
                WHERE question_log_id = ? AND status = 'PENDING'
                  AND created_at <= clock_timestamp() - (? * INTERVAL '1 millisecond')
                """, failure.code(), failure.message(), questionId, timeoutMillis);
    }

    public boolean finishSuccess(long attemptId) {
        return jdbc.update("""
                UPDATE answer_attempts_history SET status = 'SUCCESS', error_code = NULL, error_message = NULL
                WHERE attempt_id = ? AND status = 'PENDING'
                """, attemptId) == 1;
    }

    public boolean finishFailure(long attemptId, ChatFailure failure) {
        return jdbc.update("""
                UPDATE answer_attempts_history SET status = 'FAIL', error_code = ?, error_message = ?
                WHERE attempt_id = ? AND status = 'PENDING'
                """, failure.code(), failure.message(), attemptId) == 1;
    }
}
