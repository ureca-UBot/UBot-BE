package com.ubot.unanswered.repository;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import lombok.RequiredArgsConstructor;

@Repository
@RequiredArgsConstructor
public class UnansweredGroupNamingRepository {

    private final JdbcTemplate jdbcTemplate;

    public List<NamingTarget> findTargets(int limit) {
        return jdbcTemplate.query(
                """
                SELECT id, representative_question, question_count
                FROM unanswered_question_groups
                WHERE status IN ('PENDING', 'ON_HOLD')
                  AND (topic_name IS NULL OR question_count >= named_question_count * 2)
                ORDER BY naming_attempted_at NULLS FIRST, question_count DESC, id
                LIMIT ?
                """,
                (rs, rowNum) -> new NamingTarget(
                        rs.getLong("id"),
                        rs.getString("representative_question"),
                        rs.getInt("question_count")
                ),
                limit
        );
    }

    public List<String> findRecentQuestions(Long groupId, int limit) {
        return jdbcTemplate.queryForList(
                """
                SELECT sub_question
                FROM unanswered_questions
                WHERE group_id = ?
                ORDER BY created_at DESC, id DESC
                LIMIT ?
                """,
                String.class,
                groupId,
                limit
        );
    }

    public Map<String, Long> findCategoryIdsByName() {
        Map<String, Long> categories = new LinkedHashMap<>();
        jdbcTemplate.query(
                "SELECT id, name FROM faq_category WHERE deleted_at IS NULL ORDER BY name",
                rs -> {
                    categories.put(rs.getString("name"), rs.getLong("id"));
                }
        );
        return categories;
    }

    public LocalDateTime markAttempted(Long groupId) {
        return jdbcTemplate.queryForObject(
                "UPDATE unanswered_question_groups SET naming_attempted_at = LOCALTIMESTAMP WHERE id = ? RETURNING naming_attempted_at",
                LocalDateTime.class,
                groupId
        );
    }

    public boolean saveNaming(Long groupId, LocalDateTime attemptedAt, String topicName, Long suggestedCategoryId,
            int questionCount) {
        return jdbcTemplate.update(
                """
                UPDATE unanswered_question_groups
                SET topic_name = ?, suggested_category_id = ?, named_question_count = ?
                WHERE id = ?
                  AND status IN ('PENDING', 'ON_HOLD')
                  AND naming_attempted_at = ?
                """,
                topicName,
                suggestedCategoryId,
                questionCount,
                groupId,
                attemptedAt
        ) == 1;
    }

    public record NamingTarget(Long id, String representativeQuestion, int questionCount) {
    }
}
