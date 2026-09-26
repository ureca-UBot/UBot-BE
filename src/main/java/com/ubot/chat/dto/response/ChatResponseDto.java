package com.ubot.chat.dto.response;

import com.ubot.chat.entity.AnswerAttemptsHistory;

public record ChatResponseDto(
		String answer,
		String status,
		String idempotencyKey,
		int attemptCount,
		boolean retryable
) {
	public static ChatResponseDto from(AnswerAttemptsHistory attempt, String answer, boolean retryable) {
		return new ChatResponseDto(
				answer,
				attempt.getStatus(),
				attempt.getIdempotencyKey(),
				attempt.getAttemptCount(),
				retryable
		);
	}

	public static ChatResponseDto createSuccessAnswer(String answer) {
		return new ChatResponseDto(answer, "SUCCESS", null, 0, false);
	}

	public static ChatResponseDto createFailureAnswer(String reason) {
		return new ChatResponseDto(reason, "FAIL", null, 0, false);
	}
}
