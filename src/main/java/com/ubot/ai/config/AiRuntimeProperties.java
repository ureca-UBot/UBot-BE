package com.ubot.ai.config;

import org.springframework.beans.factory.InitializingBean;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.StringUtils;

@ConfigurationProperties(prefix = "app.ai")
public class AiRuntimeProperties implements InitializingBean {

    private String mode = "ollama";
    private String chatEngine;
    private String embeddingEngine;
    private int embeddingProfileVersion = 1;
    
    public int getEmbeddingProfileVersion() {
        return embeddingProfileVersion;
    }
    public void setEmbeddingProfileVersion(int embeddingProfileVersion) {
        this.embeddingProfileVersion = embeddingProfileVersion;
    }
    
    @Override
    public void afterPropertiesSet() {
        AiMode aiMode = AiMode.from(mode);

        if (aiMode == AiMode.CUSTOM) {
            if (!StringUtils.hasText(chatEngine)) {
                throw new IllegalStateException(
                        "AI_MODE=custom이면 AI_CHAT_ENGINE이 필요합니다."
                );
            }

            if (!StringUtils.hasText(embeddingEngine)) {
                throw new IllegalStateException(
                        "AI_MODE=custom이면 AI_EMBEDDING_ENGINE이 필요합니다."
                );
            }

            AiEngine.from(chatEngine);
            AiEngine.from(embeddingEngine);
            return;
        }

        if (StringUtils.hasText(chatEngine)
                || StringUtils.hasText(embeddingEngine)) {
            throw new IllegalStateException(
                    "AI_CHAT_ENGINE과 AI_EMBEDDING_ENGINE은 "
                            + "AI_MODE=custom일 때만 사용할 수 있습니다."
            );
        }
    }

    public AiMode resolvedMode() {
        return AiMode.from(mode);
    }

    public AiEngine resolvedChatEngine() {
        return switch (resolvedMode()) {
            case OLLAMA -> AiEngine.OLLAMA;
            case VLLM -> AiEngine.VLLM;
            case CUSTOM -> AiEngine.from(chatEngine);
        };
    }

    public AiEngine resolvedEmbeddingEngine() {
        return switch (resolvedMode()) {
            case OLLAMA -> AiEngine.OLLAMA;
            case VLLM -> AiEngine.VLLM;
            case CUSTOM -> AiEngine.from(embeddingEngine);
        };
    }

    public String getMode() {
        return mode;
    }

    public void setMode(String mode) {
        this.mode = mode;
    }

    public String getChatEngine() {
        return chatEngine;
    }

    public void setChatEngine(String chatEngine) {
        this.chatEngine = chatEngine;
    }

    public String getEmbeddingEngine() {
        return embeddingEngine;
    }

    public void setEmbeddingEngine(String embeddingEngine) {
        this.embeddingEngine = embeddingEngine;
    }
}