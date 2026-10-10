package com.ubot.ai.config;

import java.util.Locale;

public enum AiEngine {
    OLLAMA,
    VLLM;

    public static AiEngine from(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("AI 엔진 값은 필수입니다.");
        }

        try {
            return valueOf(value.strip().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException(
                    "지원하지 않는 AI 엔진입니다: " + value
                            + " (허용값: ollama, vllm)",
                    exception
            );
        }
    }
}