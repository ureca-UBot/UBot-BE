package com.ubot.unanswered.repository;

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
                ORDER BY question_count DESC, id
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

    public void saveNaming(Long groupId, String topicName, Long suggestedCategoryId, int questionCount) {
        jdbcTemplate.update(
                """
                UPDATE unanswered_question_groups
                SET topic_name = ?, suggested_category_id = ?, named_question_count = ?
                WHERE id = ?
                """,
                topicName,
                suggestedCategoryId,
                questionCount,
                groupId
        );
    }

    public record NamingTarget(Long id, String representativeQuestion, int questionCount) {
    }
}
