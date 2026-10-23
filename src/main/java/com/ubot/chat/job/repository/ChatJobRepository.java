package com.ubot.chat.job.repository;

import com.ubot.chat.job.entity.ChatJob;
import jakarta.persistence.LockModeType;
import java.time.LocalDateTime;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ChatJobRepository extends JpaRepository<ChatJob, Long> {
	Optional<ChatJob> findByAttemptId(Long attemptId);

	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("select j from ChatJob j where j.id = :jobId")
	Optional<ChatJob> findJobForLock(@Param("jobId") Long jobId);

	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("""
			select j from ChatJob j
			where j.id = :jobId
			  and j.status = com.ubot.chat.job.entity.ChatJobStatus.QUEUED
			""")
	Optional<ChatJob> findQueuedJobForLock(@Param("jobId") Long jobId);

	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("""
			select j from ChatJob j
			where j.id = :jobId
			  and j.status = com.ubot.chat.job.entity.ChatJobStatus.RUNNING
			  and coalesce(j.heartbeatAt, j.startedAt) < :startedBefore
			""")
	Optional<ChatJob> findRunningJobStartedBeforeForLock(
			@Param("jobId") Long jobId, @Param("startedBefore") LocalDateTime startedBefore);
}
