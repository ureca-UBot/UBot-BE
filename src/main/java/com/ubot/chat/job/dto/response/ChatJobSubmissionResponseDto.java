package com.ubot.chat.job.dto.response;

import com.ubot.chat.entity.AnswerAttemptsHistory;
import com.ubot.chat.job.entity.ChatJob;
import com.ubot.chat.job.entity.ChatJobStatus;
import java.util.UUID;

// 답변은 GET /chat/jobs/{jobId}로 조회함
public record ChatJobSubmissionResponseDto(
		Long jobId,
		ChatJobStatus status,
		UUID conversationId,
		String idempotencyKey,
		int attemptCount
) {
	public static ChatJobSubmissionResponseDto of(ChatJob job, AnswerAttemptsHistory attempt) {
		return new ChatJobSubmissionResponseDto(
				job.getId(), job.getStatus(), null, attempt.getIdempotencyKey(), attempt.getAttemptCount());
	}

	public ChatJobSubmissionResponseDto withConversationId(UUID conversationId) {
		return new ChatJobSubmissionResponseDto(jobId, status, conversationId, idempotencyKey, attemptCount);
	}
}
