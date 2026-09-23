package com.ubot.chat.mvp.domain;

/** One generation attempt; attemptCount includes the initial call (1..3). */
public record ChatAttempt(
        long attemptId, long questionId, long userId, long sessionId, String question, int attemptCount,
        String status, String idempotencyKey, String answer, String errorCode, String errorMessage) {
    public boolean pending() { return "PENDING".equals(status); }
    public boolean successful() { return "SUCCESS".equals(status); }
}
