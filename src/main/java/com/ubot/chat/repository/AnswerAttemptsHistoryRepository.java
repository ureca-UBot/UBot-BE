package com.ubot.chat.repository;

import com.ubot.chat.entity.AnswerAttemptsHistory;
import com.ubot.common.ErrorCode;
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
			@Param("attemptCount") int attemptCount
	);

	Optional<AnswerAttemptsHistory> findFirstByUserIdAndIdempotencyKeyOrderByAttemptCountDesc(
			Long userId,
			String idempotencyKey
	);

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
			@Param("attemptCount") int attemptCount
	);

	Optional<AnswerAttemptsHistory> findFirstByUserIdIsNullAndConversationIdAndIdempotencyKeyOrderByAttemptCountDesc(
			Long conversationId,
			String idempotencyKey
	);

	@Query("""
			select count(distinct a.idempotencyKey)
			from AnswerAttemptsHistory a
			where a.conversationId = :conversationId
			  and a.idempotencyKey <> :excludedIdempotencyKey
			  and (a.status in ('PENDING', 'SUCCESS')
			    or a.errorCode = :noFaq
			    or a.errorCode = :insufficientFaq)
			""")
	long countGuestQuestions(
			@Param("conversationId") Long conversationId,
			@Param("excludedIdempotencyKey") String excludedIdempotencyKey,
			@Param("noFaq") ErrorCode noFaq,
			@Param("insufficientFaq") ErrorCode insufficientFaq
	);

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
			@Param("errorMessage") String errorMessage
	);
}
