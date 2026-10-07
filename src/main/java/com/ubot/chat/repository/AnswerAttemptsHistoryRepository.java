package com.ubot.chat.repository;

import com.ubot.chat.entity.AnswerAttemptsHistory;
import com.ubot.common.ErrorCode;
import com.ubot.faq.enums.Intent;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AnswerAttemptsHistoryRepository extends JpaRepository<AnswerAttemptsHistory, Long> {
	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("""
			select a
			from AnswerAttemptsHistory a
			where a.userId = :userId
			  and a.idempotencyKey = :idempotencyKey
			  and a.attemptCount = :attemptCount
			""")
	Optional<AnswerAttemptsHistory> findInitialAttemptsForLock(
			@Param("userId") Long userId,
			@Param("idempotencyKey") String idempotencyKey,
			@Param("attemptCount") int attemptCount);

	Optional<AnswerAttemptsHistory> findFirstByUserIdAndIdempotencyKeyOrderByAttemptCountDesc(
			Long userId,
			String idempotencyKey);

	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("""
			select a
			from AnswerAttemptsHistory a
			where a.userId is null
			  and a.conversationId = :conversationId
			  and a.idempotencyKey = :idempotencyKey
			  and a.attemptCount = :attemptCount
			""")
	Optional<AnswerAttemptsHistory> findGuestInitialAttemptsForLock(
			@Param("conversationId") Long conversationId,
			@Param("idempotencyKey") String idempotencyKey,
			@Param("attemptCount") int attemptCount);

	Optional<AnswerAttemptsHistory> findFirstByUserIdIsNullAndConversationIdAndIdempotencyKeyOrderByAttemptCountDesc(
			Long conversationId,
			String idempotencyKey);

	// 의도 재검색 attempt는 게스트 질문 횟수에 포함하지 않습니다. (원본 질문이 이미 한 번 셌습니다.)
	@Query("""
			select count(distinct a.idempotencyKey)
			from AnswerAttemptsHistory a
			where a.conversationId = :conversationId
			  and a.idempotencyKey <> :excludedIdempotencyKey
			  and a.sourceAttemptId is null
			  and (a.status in ('PENDING', 'SUCCESS')
			    or a.errorCode = :noFaq
			    or a.errorCode = :insufficientFaq)
			""")
	long countGuestQuestions(
			@Param("conversationId") Long conversationId,
			@Param("excludedIdempotencyKey") String excludedIdempotencyKey,
			@Param("noFaq") ErrorCode noFaq,
			@Param("insufficientFaq") ErrorCode insufficientFaq);

	/** 같은 원본 attempt를 같은 intent로 이미 재검색했는지 확인합니다. */
	boolean existsBySourceAttemptIdAndIntent(Long sourceAttemptId, Intent intent);

	@Modifying(clearAutomatically = true, flushAutomatically = true)
	@Query("""
			update AnswerAttemptsHistory a
			set a.userId = :userId
			where a.conversationId = :conversationId and a.userId is null
			""")
	int assignGuestAttemptsToUser(@Param("conversationId") Long conversationId, @Param("userId") Long userId);

	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("select a from AnswerAttemptsHistory a where a.id = :attemptId")
	Optional<AnswerAttemptsHistory> findAttemptForLock(@Param("attemptId") Long attemptId);

	@Modifying(clearAutomatically = true, flushAutomatically = true)
	@Query("""
			update AnswerAttemptsHistory a
			set a.status = 'FAIL', a.errorCode = :errorCode, a.errorMessage = :errorMessage
			where a.id = :attemptId and a.status = 'PENDING'
			""")
	int updatePendingAttemptToFail(
			@Param("attemptId") Long attemptId,
			@Param("errorCode") ErrorCode errorCode,
			@Param("errorMessage") String errorMessage);
}