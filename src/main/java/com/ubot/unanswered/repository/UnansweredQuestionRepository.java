package com.ubot.unanswered.repository;

import com.ubot.unanswered.entity.UnansweredQuestion;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface UnansweredQuestionRepository extends JpaRepository<UnansweredQuestion, Long> {
	boolean existsByAttemptIdAndSubQuestionIndex(Long attemptId, int subQuestionIndex);

	/**
	 * 같은 질문의 다른 시도(재시도는 최초 시도의 멱등키를 공유합니다)가 같은 하위 질문을 이미 기록했는지 확인합니다.
	 * 재시도는 하위 질문을 다시 만들어 순번이 바뀔 수 있으므로 순번이 아니라 문장으로 비교합니다.
	 */
	@Query(value = """
			SELECT EXISTS (
			    SELECT 1
			    FROM unanswered_questions uq
			    JOIN answer_attempts_history recorded ON recorded.attempt_id = uq.attempt_id
			    JOIN answer_attempts_history current_attempt
			      ON current_attempt.idempotency_key = recorded.idempotency_key
			    WHERE current_attempt.attempt_id = :attemptId
			      AND uq.sub_question = :subQuestion
			)
			""", nativeQuery = true)
	boolean existsInSameQuestion(@Param("attemptId") Long attemptId, @Param("subQuestion") String subQuestion);

	List<UnansweredQuestion> findAllByGroupIdOrderByCreatedAtDesc(Long groupId);
}
