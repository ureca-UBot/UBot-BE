package com.ubot.ai.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** 전체 출력 검사와 추가 LLM 판정을 제어합니다. 입력 검사와 답변 생성에는 적용하지 않습니다. */
@ConfigurationProperties(prefix = "app.ai.output-guard")
public class AiOutputGuardProperties {

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
