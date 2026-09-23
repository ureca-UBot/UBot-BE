package com.ubot.chat.mvp.repository;

import com.ubot.chat.mvp.dto.ChatSessionResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
public class ChatMvpSessionRepository {
    private final JdbcTemplate jdbc;

    public ChatSessionResponse create(long userId) {
        return jdbc.queryForObject("""
                INSERT INTO chat_sessions (user_id) VALUES (?) RETURNING session_id, created_at
                """, (row, index) -> new ChatSessionResponse(row.getLong("session_id"),
                row.getTimestamp("created_at").toLocalDateTime()), userId);
    }

    public boolean existsOwned(long userId, long sessionId) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS(SELECT 1 FROM chat_sessions WHERE session_id = ? AND user_id = ?)
                """, Boolean.class, sessionId, userId));
    }
}
