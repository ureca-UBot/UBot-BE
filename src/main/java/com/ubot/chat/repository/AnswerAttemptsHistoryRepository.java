package com.ubot.chat.repository;

import com.ubot.chat.entity.AnswerAttemptsHistory;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AnswerAttemptsHistoryRepository extends JpaRepository<AnswerAttemptsHistory, Long> {
	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("""
			select a
			from AnswerAttemptsHistory a
			where a.idempotencyKey = :idempotencyKey
			  and a.attemptCount = :attemptCount
			""")
	Optional<AnswerAttemptsHistory> findInitialAttemptsForLock(
			@Param("idempotencyKey") String idempotencyKey,
			@Param("attemptCount") int attemptCount
	);

	Optional<AnswerAttemptsHistory> findFirstByIdempotencyKeyOrderByAttemptCountDesc(
			String idempotencyKey
	);
}
