package com.ubot.chat.mvp.repository;

import java.util.Optional;
import java.time.LocalDateTime;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** JDBC avoids changing the shared FAQ/User JPA entities. */
@Repository
@RequiredArgsConstructor
public class QuestionLogRepository {
    private final JdbcTemplate jdbc;

    public record Question(long id, long userId, long sessionId, String text, LocalDateTime createdAt) {}

    public Question create(long userId, long sessionId, String question) {
        // The agreed llm_question column is NOT NULL. Empty means no completed answer yet.
        return jdbc.queryForObject("""
                INSERT INTO question_log (user_id, session_id, user_question, llm_question, created_at)
                VALUES (?, ?, ?, '', clock_timestamp()) RETURNING id, created_at
                """, (row, index) -> new Question(row.getLong("id"), userId, sessionId, question,
                row.getTimestamp("created_at").toLocalDateTime()), userId, sessionId, question);
    }

    public Optional<Question> lockOwned(long userId, long questionId) {
        return jdbc.query("""
                SELECT id, user_id, session_id, user_question, created_at FROM question_log
                WHERE id = ? AND user_id = ? FOR UPDATE
                """, (row, index) -> new Question(row.getLong("id"), row.getLong("user_id"),
                row.getLong("session_id"), row.getString("user_question"),
                row.getTimestamp("created_at").toLocalDateTime()), questionId, userId).stream().findFirst();
    }

    public void saveAnswer(long questionId, String answer) {
        jdbc.update("UPDATE question_log SET llm_question = ? WHERE id = ?", answer, questionId);
    }
}
