package com.ubot.chat.mvp.dto;

import com.ubot.chat.mvp.domain.ChatAttempt;
import com.ubot.chat.mvp.domain.ChatFailure;

/** SSE data and result-lookup response. A completed answer is delivered in full, once. */
public record ChatAttemptResponse(
        long sessionId, long questionId, long attemptId, String idempotencyKey, String status, int attemptCount, int maxAttempts,
        int remainingAttempts, boolean success, boolean retryable,
        String answer, String errorCode, String message) {
    public static final int MAX_ATTEMPTS = 3;

    public static ChatAttemptResponse from(ChatAttempt attempt) {
        boolean retryable = "FAIL".equals(attempt.status())
                && attempt.attemptCount() < MAX_ATTEMPTS
                && ChatFailure.isRetryable(attempt.errorCode());
        String message = attempt.pending() ? "답변 생성 중입니다."
                : attempt.successful() ? "답변 생성이 완료되었습니다." : attempt.errorMessage();
        if ("FAIL".equals(attempt.status()) && attempt.attemptCount() >= MAX_ATTEMPTS) {
            message = "총 3회 시도했지만 AI 답변을 생성하지 못했습니다. "
                    + (message == null ? "" : message);
        }
        return new ChatAttemptResponse(attempt.sessionId(), attempt.questionId(), attempt.attemptId(), attempt.idempotencyKey(), attempt.status(),
                attempt.attemptCount(), MAX_ATTEMPTS,
                Math.max(0, MAX_ATTEMPTS - attempt.attemptCount()), attempt.successful(), retryable,
                attempt.answer(), attempt.errorCode(), message);
    }

    public static ChatAttemptResponse storageFailure(ChatAttempt attempt) {
        return new ChatAttemptResponse(attempt.sessionId(), attempt.questionId(), attempt.attemptId(), attempt.idempotencyKey(), "UNKNOWN",
                attempt.attemptCount(), MAX_ATTEMPTS, 0, false, false, null,
                "CHAT_RECORD_UNAVAILABLE", "처리 결과의 저장 여부를 확인할 수 없습니다. 결과를 다시 조회해주세요.");
    }
}
