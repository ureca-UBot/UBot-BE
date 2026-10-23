package com.ubot.telemap.repository;

import java.time.LocalDateTime;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import com.ubot.ranking.enums.RegionSido;

import lombok.RequiredArgsConstructor;

@Repository
@RequiredArgsConstructor
public class TelemapRetentionRepository {

    private final JdbcTemplate jdbcTemplate;

    public int deleteExpiredClusters(
            RegionSido region,
            long embeddingProfileId,
            LocalDateTime createdBefore,
            LocalDateTime inactiveSince
    ) {
        return jdbcTemplate.update(
                """
                DELETE FROM surging_clusters
                WHERE region = ?
                  AND embedding_profile_id = ?
                  AND created_at < ?
                  AND last_occurred_at < ?
                """,
                region.name(), embeddingProfileId, createdBefore, inactiveSince
        );
    }

    public int anonymizeAlertSnapshots(LocalDateTime occurredBefore) {
        return jdbcTemplate.update(
                """
                UPDATE surging_alerts alert
                SET question_snapshots = (
                    SELECT jsonb_agg(
                            CASE
                                WHEN (item.snapshot ->> 'occurredAt')::timestamp < ?
                                    THEN jsonb_set(item.snapshot, '{question}', '""'::jsonb, false)
                                ELSE item.snapshot
                            END
                            ORDER BY item.ordinality)
                    FROM jsonb_array_elements(alert.question_snapshots)
                         WITH ORDINALITY AS item(snapshot, ordinality)
                )
                WHERE EXISTS (
                      SELECT 1
                      FROM jsonb_array_elements(alert.question_snapshots) AS snapshots(snapshot)
                      WHERE (snapshots.snapshot ->> 'occurredAt')::timestamp < ?
                        AND snapshots.snapshot ->> 'question' <> ''
                  )
                """,
                occurredBefore,
                occurredBefore
        );
    }

    public int anonymizeClusterRepresentativeQuestions(LocalDateTime createdBefore) {
        return jdbcTemplate.update(
                """
                UPDATE surging_clusters sc
                SET representative_question = '',
                    updated_at = CURRENT_TIMESTAMP
                WHERE sc.created_at < ?
                  AND sc.representative_question <> ''
                """,
                createdBefore
        );
    }

    public int deleteExpiredClusters(
            LocalDateTime createdBefore,
            LocalDateTime inactiveSince
    ) {
        return jdbcTemplate.update(
                """
                DELETE FROM surging_clusters sc
                WHERE sc.created_at < ?
                  AND sc.last_occurred_at < ?
                """,
                createdBefore, inactiveSince
        );
    }
}
