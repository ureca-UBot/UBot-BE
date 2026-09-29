package com.ubot.unanswered.repository;

import java.util.Optional;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import com.pgvector.PGvector;
import com.ubot.unanswered.enums.UnansweredReason;

import lombok.RequiredArgsConstructor;

@Repository
@RequiredArgsConstructor
public class UnansweredQuestionVectorRepository {
	private final JdbcTemplate jdbcTemplate;

	public Optional<Long> findNearestGroupId(PGvector vector, double threshold){
		return jdbcTemplate.query(
				"""
				SELECT id
				FROM unanswered_question_groups
				WHERE status IN ('PENDING', 'ON_HOLD')
				  AND 1 - (centroid <=> ?) >= ?
				ORDER BY centroid <=> ?
				LIMIT 1
				""",
				(rs, rowNum) -> rs.getLong("id"),
				vector, threshold, vector
		).stream().findFirst();
	}

	public Long saveGroup(String question, PGvector vector, Long relatedFaqId){
		return jdbcTemplate.queryForObject(
				"""
				INSERT INTO unanswered_question_groups (representative_question, centroid, related_faq_id)
				VALUES (?, ?, ?)
				RETURNING id
				""",
				Long.class,
				question, vector, relatedFaqId
		);
	}

	public void saveQuestion(
			Long attemptId,
			Long groupId,
			String question,
			PGvector vector,
			UnansweredReason reason,
			Long bestFaqId,
			Double bestSimilarity
	){
		jdbcTemplate.update(
				"""
				INSERT INTO unanswered_questions
				    (attempt_id, group_id, question, question_vector, reason, best_faq_id, best_similarity)
				VALUES (?, ?, ?, ?, ?, ?, ?)
				""",
				attemptId, groupId, question, vector, reason.name(), bestFaqId, bestSimilarity
		);
	}

	public void updateGroupCentroid(Long groupId){
		jdbcTemplate.update(
				"""
				UPDATE unanswered_question_groups g
				SET centroid = s.centroid,
				    question_count = s.question_count,
				    last_occurred_at = CURRENT_TIMESTAMP,
				    updated_at = CURRENT_TIMESTAMP
				FROM (
				    SELECT AVG(question_vector) AS centroid, COUNT(*) AS question_count
				    FROM unanswered_questions
				    WHERE group_id = ?
				) s
				WHERE g.id = ?
				""",
				groupId, groupId
		);
	}
}
