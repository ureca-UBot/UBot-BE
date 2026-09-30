package com.ubot.unanswered.repository;

import com.ubot.unanswered.entity.UnansweredQuestion;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface UnansweredQuestionRepository extends JpaRepository<UnansweredQuestion, Long> {
	boolean existsByAttemptId(Long attemptId);

	List<UnansweredQuestion> findAllByGroupIdOrderByCreatedAtDesc(Long groupId);
}
