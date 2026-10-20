package com.ubot.outbox;

import java.util.Optional;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import lombok.RequiredArgsConstructor;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@Repository
@RequiredArgsConstructor
public class OutboxEventRepository {

    private static final JsonMapper JSON_MAPPER = JsonMapper.builder().build();

    private final JdbcTemplate jdbcTemplate;

    public void enqueue(OutboxEventType type, String dedupKey, Object payload) {
        jdbcTemplate.update(
                """
                INSERT INTO outbox_events (event_type, dedup_key, payload)
                VALUES (?, ?, ?::jsonb)
                ON CONFLICT (dedup_key) DO NOTHING
                """,
                type.name(),
                type.name() + ":" + dedupKey,
                JSON_MAPPER.writeValueAsString(payload)
        );
    }

    public Optional<OutboxEvent> lockNext(int maxRetries) {
        return jdbcTemplate.query(
                """
                SELECT id, event_type, payload
                FROM outbox_events
                WHERE retry_count < ?
                ORDER BY id
                LIMIT 1
                FOR UPDATE SKIP LOCKED
                """,
                (rs, rowNum) -> new OutboxEvent(
                        rs.getLong("id"),
                        OutboxEventType.valueOf(rs.getString("event_type")),
                        JSON_MAPPER.readTree(rs.getString("payload"))
                ),
                maxRetries
        ).stream().findFirst();
    }

    public void delete(long id) {
        jdbcTemplate.update("DELETE FROM outbox_events WHERE id = ?", id);
    }

    public void increaseRetryCount(long id) {
        jdbcTemplate.update("UPDATE outbox_events SET retry_count = retry_count + 1 WHERE id = ?", id);
    }

    public record OutboxEvent(long id, OutboxEventType type, JsonNode payload) {
    }
}
