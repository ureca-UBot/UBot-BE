package com.ubot.chat.dto.response;

import com.ubot.chat.entity.AnswerAttemptsHistory;

// store는 매장 질문의 성공 답변에만 담기며(위치 필요 또는 지도 결과), 그 외 답변과 실패 응답에서는 null입니다.
public record ChatResponseDto(
		String answer,
		String status,
		String idempotencyKey,
		int attemptCount,
		boolean retryable,
		ChatStoreDto store
) {
	public ChatResponseDto(String answer, String status, String idempotencyKey, int attemptCount, boolean retryable) {
		this(answer, status, idempotencyKey, attemptCount, retryable, null);
	}

	public static ChatResponseDto from(AnswerAttemptsHistory attempt, String answer, boolean retryable) {
		return from(attempt, answer, retryable, null);
	}

	public static ChatResponseDto from(
			AnswerAttemptsHistory attempt, String answer, boolean retryable, ChatStoreDto store
	) {
		return new ChatResponseDto(
				answer,
				attempt.getStatus(),
				attempt.getIdempotencyKey(),
				attempt.getAttemptCount(),
				retryable,
				store
		);
	}

	public static ChatResponseDto createSuccessAnswer(String answer) {
		return new ChatResponseDto(answer, "SUCCESS", null, 0, false);
	}

	public static ChatResponseDto createFailureAnswer(String reason) {
		return new ChatResponseDto(reason, "FAIL", null, 0, false);
	}
}
