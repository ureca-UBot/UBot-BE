package com.ubot.chat.repository;

import com.ubot.chat.entity.QuestionLog;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;

public interface QuestionLogRepository extends JpaRepository<QuestionLog, Long> {
	@Query ("""
		select (count(ql) > 0)
		from QuestionLog ql
		where
			ql.userId = :userId
		and ql.normalizedQuestion = :normalizedQuestion
		and ql.createdAt >= :startAt
		and ql.createdAt < :endAt
		"""
	)
	boolean existsByUserIdAndNormalizedQuestionInRankingWindow(
			@Param("userId") Long userId,
			@Param("normalizedQuestion") String normalizedQuestion,
			@Param("startAt") LocalDateTime startAt,
			@Param("endAt") LocalDateTime endAt
	);

	@Modifying(clearAutomatically = true, flushAutomatically = true)
	@Query("""
			update QuestionLog ql
			set ql.userId = :userId
			where ql.conversationId = :conversationId and ql.userId is null
			""")
	int assignGuestQuestionLogsToUser(@Param("conversationId") Long conversationId, @Param("userId") Long userId);

	@Query(
			value = "SELECT pg_advisory_xact_lock(:lockKey)",
			nativeQuery = true
	)
	void acquireAdvisoryLock(@Param("lockKey") long lockKey);
}
