package com.ubot.chat.mvp.domain;

import java.util.Set;

/** Only transient generation/search failures offer a retry. Never expose raw exception messages. */
public record ChatFailure(String code, String message) {
    private static final Set<String> RETRYABLE = Set.of(
            "EM-001", "EM-002", "EM-003", "LLM_SERVICE_UNAVAILABLE", "LLM_TIMEOUT",
            "LLM_RESPONSE_INVALID", "CHAT_VECTOR_UNAVAILABLE", "CHAT_TIMEOUT",
            "CHAT_WORKER_BUSY", "CHAT_GENERATION_ERROR");

    public static boolean isRetryable(String code) {
        return code != null && RETRYABLE.contains(code);
    }

    public static ChatFailure timeout() {
        return new ChatFailure("CHAT_TIMEOUT", "답변 생성 시간이 초과되었습니다.");
    }
}
