package com.ubot.chat.dto.response;

import com.ubot.ai.dto.StoreMapResult;
import com.ubot.chat.entity.AnswerAttemptsHistory;

// storeMap은 매장 조회 도구가 실행된 성공 답변에만 담기며, 화면이 지도에 매장을 그릴 때 씁니다.
public record ChatResponseDto(
		String answer,
		String status,
		String idempotencyKey,
		int attemptCount,
		boolean retryable,
		StoreMapResult storeMap
) {
	public ChatResponseDto(String answer, String status, String idempotencyKey, int attemptCount, boolean retryable) {
		this(answer, status, idempotencyKey, attemptCount, retryable, null);
	}

	public static ChatResponseDto from(AnswerAttemptsHistory attempt, String answer, boolean retryable) {
		return from(attempt, answer, retryable, null);
	}

	public static ChatResponseDto from(
			AnswerAttemptsHistory attempt, String answer, boolean retryable, StoreMapResult storeMap
	) {
		return new ChatResponseDto(
				answer,
				attempt.getStatus(),
				attempt.getIdempotencyKey(),
				attempt.getAttemptCount(),
				retryable,
				storeMap
		);
	}

	public static ChatResponseDto createSuccessAnswer(String answer) {
		return new ChatResponseDto(answer, "SUCCESS", null, 0, false);
	}

	public static ChatResponseDto createFailureAnswer(String reason) {
		return new ChatResponseDto(reason, "FAIL", null, 0, false);
	}
}
