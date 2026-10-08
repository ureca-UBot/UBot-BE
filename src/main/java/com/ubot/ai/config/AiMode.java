package com.ubot.ai.config;

import java.util.Locale;

public enum AiMode {
    OLLAMA,
    VLLM,
    CUSTOM;

    public static AiMode from(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("AI_MODE는 필수입니다.");
        }

        try {
            return valueOf(value.strip().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException(
                    "지원하지 않는 AI_MODE입니다: " + value
                            + " (허용값: ollama, vllm, custom)",
                    exception
            );
        }
    }
}