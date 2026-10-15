package com.ubot.chat.job.repository;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

// Worker 선점·반납 SQL. 메서드마다 한 문장 트랜잭션. QUEUED→RUNNING으로 바꾼 한 실행만 답변을 만듦
@Repository
public class ChatJobQueueRepository {
	private final NamedParameterJdbcTemplate jdbc;
	@Value("${app.chat.worker.max-queue-wait:10m}")
	private Duration maxQueueWait = Duration.ofMinutes(10);

	public ChatJobQueueRepository(NamedParameterJdbcTemplate jdbc) {
		this.jdbc = jdbc;
	}

	// 이미 실행 중이거나 끝났으면 빈 값. now는 애플리케이션 시계
	public Optional<LocalDateTime> claim(long jobId, LocalDateTime now) {
		List<LocalDateTime> claimed = jdbc.query("""
				UPDATE chat_jobs
				SET execution_status = 'RUNNING',
				    started_at = :now, heartbeat_at = :now, updated_at = :now
				WHERE job_id = :jobId
				  AND execution_status = 'QUEUED'
				  AND created_at >= CURRENT_TIMESTAMP - make_interval(secs => :maxWait)
				RETURNING started_at
				""",
				new MapSqlParameterSource()
						.addValue("jobId", jobId)
						.addValue("maxWait", maxQueueWait.toMillis() / 1000.0)
						.addValue("now", now),
				(rs, rowNum) -> rs.getObject("started_at", LocalDateTime.class));
		return claimed.stream().findFirst();
	}

	// heartbeat가 startedBefore보다 오래된 실행만 넘김. 이전 실행은 started_at이 달라 결과를 기록 못 함
	public Optional<LocalDateTime> takeOver(long jobId, LocalDateTime now, LocalDateTime startedBefore) {
		List<LocalDateTime> taken = jdbc.query("""
				UPDATE chat_jobs
				SET started_at = :now, heartbeat_at = :now, updated_at = :now
				WHERE job_id = :jobId
				  AND execution_status = 'RUNNING'
				  AND COALESCE(heartbeat_at, started_at) < :startedBefore
				RETURNING started_at
				""",
				new MapSqlParameterSource()
						.addValue("jobId", jobId)
						.addValue("now", now)
						.addValue("startedBefore", startedBefore),
				(rs, rowNum) -> rs.getObject("started_at", LocalDateTime.class));
		return taken.stream().findFirst();
	}

	// 그새 다른 실행이 가져갔으면 건드리지 않음
	public int release(long jobId, LocalDateTime startedAt) {
		return jdbc.update("""
				UPDATE chat_jobs
				SET execution_status = 'QUEUED',
				    started_at = NULL, heartbeat_at = NULL, updated_at = CURRENT_TIMESTAMP
				WHERE job_id = :jobId
				  AND execution_status = 'RUNNING'
				  AND started_at = :startedAt
				""",
				new MapSqlParameterSource()
						.addValue("jobId", jobId)
						.addValue("startedAt", startedAt));
	}

	public List<Long> findQueuedOlderThan(Duration maxWait, int limit) {
		return findQueuedOlderThanAfter(maxWait, limit, 0);
	}

	public List<Long> findQueuedOlderThanAfter(Duration maxWait, int limit, long afterId) {
		return jdbc.queryForList("""
				SELECT job_id
				FROM chat_jobs
				WHERE execution_status = 'QUEUED'
				  AND job_id > :afterId
				  AND created_at < CURRENT_TIMESTAMP - make_interval(secs => :maxWaitSeconds)
				ORDER BY job_id
				LIMIT :limit
				""",
				new MapSqlParameterSource()
						.addValue("afterId", afterId)
						.addValue("maxWaitSeconds", maxWait.toMillis() / 1000.0)
						.addValue("limit", limit),
				Long.class);
	}

	// started_at·heartbeat_at은 애플리케이션 시계라 기준 시각도 같은 시계로 받음
	public List<Long> findRunningStartedBefore(LocalDateTime startedBefore, int limit) {
		return jdbc.queryForList("""
				SELECT job_id
				FROM chat_jobs
				WHERE execution_status = 'RUNNING'
				  AND COALESCE(heartbeat_at, started_at) < :startedBefore
				ORDER BY COALESCE(heartbeat_at, started_at)
				LIMIT :limit
				""",
				new MapSqlParameterSource()
						.addValue("startedBefore", startedBefore)
						.addValue("limit", limit),
				Long.class);
	}

	// 실행 토큰이 같을 때만 갱신. 0이면 실행 권한 잃음
	public int heartbeat(long jobId, LocalDateTime startedAt, LocalDateTime now) {
		return jdbc.update("""
				UPDATE chat_jobs SET heartbeat_at = :now
				WHERE job_id = :jobId AND execution_status = 'RUNNING' AND started_at = :startedAt
				""", new MapSqlParameterSource("jobId", jobId)
				.addValue("startedAt", startedAt).addValue("now", now));
	}

	public double queuedCount() {
		return jdbc.queryForObject("SELECT count(*) FROM chat_jobs WHERE execution_status = 'QUEUED'",
				new MapSqlParameterSource(), Double.class);
	}

	public double oldestQueuedSeconds() {
		return jdbc.queryForObject("""
				SELECT COALESCE(GREATEST(EXTRACT(EPOCH FROM CURRENT_TIMESTAMP - min(created_at)), 0), 0)
				FROM chat_jobs WHERE execution_status = 'QUEUED'
				""", new MapSqlParameterSource(), Double.class);
	}
}
