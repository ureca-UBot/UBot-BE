package com.ubot.chat.repository;

import com.ubot.chat.entity.AnswerAttemptsHistory;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;

public interface AnswerAttemptsHistoryRepository extends JpaRepository<AnswerAttemptsHistory, Long> {
	@Lock(LockModeType.PESSIMISTIC_WRITE)
	Optional<AnswerAttemptsHistory> findByUserIdAndIdempotencyKeyAndAttemptCount(
			Long userId,
			String idempotencyKey,
			int attemptCount
	);

	Optional<AnswerAttemptsHistory> findFirstByUserIdAndIdempotencyKeyOrderByAttemptCountDesc(
			Long userId,
			String idempotencyKey
	);
}
