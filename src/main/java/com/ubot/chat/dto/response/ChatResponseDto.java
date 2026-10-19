package com.ubot.chat.dto.response;

import com.ubot.ai.dto.UserDataCards;
import com.ubot.chat.entity.AnswerAttemptsHistory;
import java.util.UUID;

// store는 매장 질문의 성공 답변에만 담기며(위치 필요 또는 지도 결과), 그 외 답변과 실패 응답에서는 null입니다.
// userData는 가입 정보 답변에 쓰인 요금제·사용량·청구서 카드이며, 카드가 없거나 실패 응답이면 null입니다.
public record ChatResponseDto(
		String answer,
		String status,
		String idempotencyKey,
		int attemptCount,
		boolean retryable,
		ChatStoreDto store,
		UserDataCards userData,
		UUID conversationId
) {
	public ChatResponseDto(String answer, String status, String idempotencyKey, int attemptCount, boolean retryable) {
		this(answer, status, idempotencyKey, attemptCount, retryable, null);
	}

	public ChatResponseDto(
			String answer, String status, String idempotencyKey, int attemptCount, boolean retryable, ChatStoreDto store
	) {
		this(answer, status, idempotencyKey, attemptCount, retryable, store, null, null);
	}

	public ChatResponseDto withConversationId(UUID conversationId) {
		return new ChatResponseDto(answer, status, idempotencyKey, attemptCount, retryable, store, userData, conversationId);
	}

	public static ChatResponseDto from(AnswerAttemptsHistory attempt, String answer, boolean retryable) {
		return from(attempt, answer, retryable, null);
	}

	public static ChatResponseDto from(
			AnswerAttemptsHistory attempt, String answer, boolean retryable, ChatStoreDto store
	) {
		return from(attempt, answer, retryable, store, null);
	}

	public static ChatResponseDto from(
			AnswerAttemptsHistory attempt, String answer, boolean retryable, ChatStoreDto store, UserDataCards userData
	) {
		return new ChatResponseDto(
				answer,
				attempt.getStatus(),
				attempt.getIdempotencyKey(),
				attempt.getAttemptCount(),
				retryable,
				store,
				userData,
				null
		);
	}

	public static ChatResponseDto createSuccessAnswer(String answer) {
		return new ChatResponseDto(answer, "SUCCESS", null, 0, false);
	}

	public static ChatResponseDto createFailureAnswer(String reason) {
		return new ChatResponseDto(reason, "FAIL", null, 0, false);
	}
}
