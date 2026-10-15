package com.ubot.chat.job.entity;

import com.ubot.ai.progress.AnswerProgress.Phase;
import com.ubot.chat.job.dto.ChatJobResult;
import com.ubot.chat.job.dto.ChatRequestContext;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

// started_at·heartbeat_at은 SQL만 바꿈. 엔티티는 행을 잠근 트랜잭션에서만 저장하므로 읽은 값을 다시 써도 안전함
@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(name = "chat_jobs")
public class ChatJob {
	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	@Column(name = "job_id")
	private Long id;

	@Column(name = "attempt_id")
	private Long attemptId;

	@Enumerated(EnumType.STRING)
	@Column(name = "execution_status")
	private ChatJobStatus status;

	@Enumerated(EnumType.STRING)
	@Column(name = "phase")
	private Phase phase;

	@Column(name = "phase_updated_at")
	private LocalDateTime phaseUpdatedAt;

	@Column(name = "deadline_at")
	private LocalDateTime deadlineAt;

	@Column(name = "created_at")
	private LocalDateTime createdAt;

	@Column(name = "updated_at")
	private LocalDateTime updatedAt;

	// 선점 시각이자 실행 권한 확인용 토큰
	@Column(name = "started_at")
	private LocalDateTime startedAt;

	@Column(name = "finished_at")
	private LocalDateTime finishedAt;

	@Column(name = "heartbeat_at")
	private LocalDateTime heartbeatAt;

	@JdbcTypeCode(SqlTypes.JSON)
	@Column(name = "request_context")
	private ChatRequestContext requestContext;

	@JdbcTypeCode(SqlTypes.JSON)
	@Column(name = "result_payload")
	private ChatJobResult result;

	private ChatJob(Long attemptId) {
		this.attemptId = attemptId;
		this.status = ChatJobStatus.QUEUED;
		// 선점 SQL과 같은 마이크로초 정밀도
		this.createdAt = LocalDateTime.now().truncatedTo(ChronoUnit.MICROS);
		this.updatedAt = this.createdAt;
	}

	public static ChatJob queue(Long attemptId) {
		return queue(attemptId, ChatRequestContext.EMPTY);
	}

	public static ChatJob queue(Long attemptId, ChatRequestContext context) {
		return queue(attemptId, context, Duration.ofMinutes(10));
	}

	public static ChatJob queue(Long attemptId, ChatRequestContext context, Duration maxQueueWait) {
		ChatJob job = new ChatJob(attemptId);
		job.requestContext = context;
		job.phase = Phase.WAITING;
		job.phaseUpdatedAt = job.createdAt;
		job.deadlineAt = job.phaseUpdatedAt.plus(maxQueueWait);
		return job;
	}

	public void succeed(ChatJobResult result) {
		this.result = result;
		succeed();
	}

	// 늦게 끝난 이전 실행의 기록을 거절할 때 씀
	public boolean isRunningSince(LocalDateTime claimedAt) {
		return status == ChatJobStatus.RUNNING && startedAt != null && startedAt.equals(claimedAt);
	}

	public void succeed() {
		finish(ChatJobStatus.SUCCEEDED);
	}

	public void fail() {
		finish(ChatJobStatus.FAILED);
	}

	private void finish(ChatJobStatus status) {
		this.status = status;
		this.finishedAt = LocalDateTime.now();
		this.updatedAt = this.finishedAt;
		// 좌표·지역은 종료와 같은 트랜잭션에서 지움
		this.requestContext = null;
		this.phase = null;
		this.phaseUpdatedAt = this.finishedAt;
		this.deadlineAt = null;
	}
}
