package com.ubot.chat.repository;

import com.ubot.chat.entity.QuestionLog;
import org.springframework.data.jpa.repository.JpaRepository;
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

	@Query ("""
		select (count(ql) > 0)
		from QuestionLog ql
		where
			ql.normalizedQuestion = :normalizedQuestion
		and ql.createdAt >= :startAt
		and ql.createdAt < :endAt
		"""
	)
	boolean existsByNormalizedQuestionInRankingWindow(
			@Param("normalizedQuestion") String normalizedQuestion,
			@Param("startAt") LocalDateTime startAt,
			@Param("endAt") LocalDateTime endAt
	);
}
