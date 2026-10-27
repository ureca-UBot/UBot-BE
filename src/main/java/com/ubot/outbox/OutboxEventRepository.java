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
                  AND next_attempt_at <= LOCALTIMESTAMP
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

    /** FOR UPDATE로 잡은 이벤트를 다른 worker가 처리하지 못하도록 lease를 남긴다. */
    public void lease(long id, long leaseSeconds) {
        jdbcTemplate.update(
                """
                UPDATE outbox_events
                SET next_attempt_at = LOCALTIMESTAMP + make_interval(secs => ?)
                WHERE id = ?
                """,
                leaseSeconds,
                id
        );
    }

    public long countPending(OutboxEventType type, int maxRetries) {
        return jdbcTemplate.queryForObject(
                """
                SELECT COUNT(*)
                FROM outbox_events
                WHERE event_type = ?
                  AND retry_count < ?
                """,
                Long.class,
                type.name(),
                maxRetries
        );
    }

    public void delete(long id) {
        jdbcTemplate.update("DELETE FROM outbox_events WHERE id = ?", id);
    }

    public int increaseRetryCount(long id, long baseDelaySeconds) {
        return jdbcTemplate.queryForObject(
                """
                UPDATE outbox_events
                SET retry_count = retry_count + 1,
                    next_attempt_at = LOCALTIMESTAMP + make_interval(secs => ? * power(2, retry_count))
                WHERE id = ?
                RETURNING retry_count
                """,
                Integer.class,
                baseDelaySeconds,
                id
        );
    }

    public record OutboxEvent(long id, OutboxEventType type, JsonNode payload) {
    }
}
