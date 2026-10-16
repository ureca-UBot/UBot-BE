package com.ubot.chat.job.dto.response;

import com.ubot.chat.entity.AnswerAttemptsHistory;
import com.ubot.chat.job.entity.ChatJob;
import com.ubot.chat.job.entity.ChatJobStatus;
import java.util.UUID;
import java.time.Instant;
import com.ubot.ai.progress.AnswerProgress.Phase;

// 답변은 GET /chat/jobs/{jobId}로 조회함
public record ChatJobSubmissionResponseDto(
		Long jobId,
		ChatJobStatus status,
		UUID conversationId,
		String idempotencyKey,
		int attemptCount,
		Phase phase,
		Instant phaseUpdatedAt,
		Instant deadlineAt,
		Instant serverTime
) {
	public ChatJobSubmissionResponseDto(Long jobId, ChatJobStatus status, UUID conversationId,
			String idempotencyKey, int attemptCount) {
		this(jobId, status, conversationId, idempotencyKey, attemptCount, null, null, null, Instant.now());
	}

	public static ChatJobSubmissionResponseDto of(ChatJob job, AnswerAttemptsHistory attempt) {
		return new ChatJobSubmissionResponseDto(
				job.getId(), job.getStatus(), null, attempt.getIdempotencyKey(), attempt.getAttemptCount(),
				job.getPhase(), utc(job.getPhaseUpdatedAt()), utc(job.getDeadlineAt()), Instant.now());
	}

	public ChatJobSubmissionResponseDto withConversationId(UUID conversationId) {
		return new ChatJobSubmissionResponseDto(jobId, status, conversationId, idempotencyKey, attemptCount,
				phase, phaseUpdatedAt, deadlineAt, serverTime);
	}

	private static Instant utc(java.time.LocalDateTime time) {
		return time == null ? null : time.atZone(java.time.ZoneId.of("Asia/Seoul")).toInstant();
	}
}
