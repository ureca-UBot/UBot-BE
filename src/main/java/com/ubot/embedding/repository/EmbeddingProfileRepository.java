package com.ubot.embedding.repository;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
public class EmbeddingProfileRepository {

    private final JdbcTemplate jdbcTemplate;

    public Long findOrCreate(
            String provider,
            String modelName,
            int dimensions,
            int profileVersion
    ) {
        jdbcTemplate.update(
                """
                INSERT INTO embedding_profiles (
                    provider,
                    model_name,
                    dimensions,
                    profile_version
                )
                VALUES (?, ?, ?, ?)
                ON CONFLICT (
                    provider,
                    model_name,
                    dimensions,
                    profile_version
                )
                DO NOTHING
                """,
                provider,
                modelName,
                dimensions,
                profileVersion
        );

        return jdbcTemplate.queryForObject(
                """
                SELECT profile_id
                FROM embedding_profiles
                WHERE provider = ?
                  AND model_name = ?
                  AND dimensions = ?
                  AND profile_version = ?
                """,
                Long.class,
                provider,
                modelName,
                dimensions,
                profileVersion
        );
    }
}
