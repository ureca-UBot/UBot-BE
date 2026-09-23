package com.ubot.chat.dto.response;

import com.ubot.chat.entity.AnswerAttemptsHistory;

public record ChatResponseDto(
		String answer,
		boolean success,
		String status,
		String idempotencyKey,
		int attemptCount,
		boolean retryable
) {
	public ChatResponseDto(String answer, boolean success) {
		this(answer, success, success ? "SUCCESS" : "FAIL", null, 0, false);
	}

	public static ChatResponseDto from(AnswerAttemptsHistory attempt, String answer, boolean retryable) {
		return new ChatResponseDto(
				answer,
				"SUCCESS".equals(attempt.getStatus()),
				attempt.getStatus(),
				attempt.getIdempotencyKey(),
				attempt.getAttemptCount(),
				retryable
		);
	}

	public static ChatResponseDto createSuccessAnswer(String answer) {
		return new ChatResponseDto(answer, true);
	}

	public static ChatResponseDto createFailureAnswer(String reason) {
		return new ChatResponseDto(reason, false);
	}
}
