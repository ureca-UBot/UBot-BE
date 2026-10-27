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

	default boolean existsInSameQuestion(Long attemptId, String subQuestion) {
		return existsInSameQuestion(attemptId, subQuestion, "");
	}

	/**
	 * 같은 질문의 다른 시도(재시도는 최초 시도의 멱등키를 공유합니다)가 같은 하위 질문을 이미 기록했는지 확인합니다.
	 * 재시도는 하위 질문을 다시 만들어 순번·검색어가 바뀔 수 있으므로 같은 검색어 또는 같은 안전한 원문 조각으로 비교합니다.
	 * 원문 조각이 없으면 빈 문자열을 받고 검색어만 비교합니다. 전체 사용자 원문으로 대신 채우지 않습니다.
	 */
	@Query(value = """
			SELECT EXISTS (
			    SELECT 1
			    FROM unanswered_questions uq
			    JOIN answer_attempts_history recorded ON recorded.attempt_id = uq.attempt_id
			    JOIN answer_attempts_history current_attempt
			      ON current_attempt.idempotency_key = recorded.idempotency_key
			    WHERE current_attempt.attempt_id = :attemptId
			      AND (uq.sub_question = :subQuestion OR (:userText <> '' AND uq.user_text = :userText))
			)
			""", nativeQuery = true)
	boolean existsInSameQuestion(@Param("attemptId") Long attemptId, @Param("subQuestion") String subQuestion,
			@Param("userText") String userText);

	List<UnansweredQuestion> findAllByGroupIdOrderByCreatedAtDesc(Long groupId);
}
