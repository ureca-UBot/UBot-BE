package com.ubot.ai.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** 입력 안전 검사만 제어합니다. 공통 질문 정리와 기존 분류·멀티턴 경로는 유지합니다. */
@ConfigurationProperties(prefix = "app.ai.input-guard")
public class AiInputGuardProperties {

    private boolean enabled = true;
    private boolean llmEnabled = true;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public boolean isLlmEnabled() {
        return llmEnabled;
    }

    public void setLlmEnabled(boolean llmEnabled) {
        this.llmEnabled = llmEnabled;
    }
}
