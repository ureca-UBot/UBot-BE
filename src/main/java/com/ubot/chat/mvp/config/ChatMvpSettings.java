package com.ubot.chat.mvp.config;

import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.Assert;

/** Local module settings; no change to the shared application.yml or executor configuration. */
@Component
public record ChatMvpSettings(
        @Value("${chat.mvp.timeout:150s}") Duration timeout,
        @Value("${chat.mvp.workers:4}") int workers,
        @Value("${chat.mvp.queue-capacity:16}") int queueCapacity,
        @Value("${chat.mvp.top-k:3}") int topK,
        @Value("${chat.mvp.confidence-threshold:0.75}") double confidenceThreshold,
        @Value("${spring.ai.ollama.chat.options.model:${OLLAMA_CHAT_MODEL:}}") String llmModel,
        @Value("${ollama.embedding.model:}") String embeddingModel) {
    public ChatMvpSettings {
        Assert.isTrue(timeout != null && timeout.toMillis() >= 1000, "채팅 제한 시간은 1초 이상이어야 합니다.");
        Assert.isTrue(workers > 0 && queueCapacity > 0 && topK > 0, "작업 수와 검색 개수는 양수여야 합니다.");
        Assert.isTrue(confidenceThreshold >= 0 && confidenceThreshold <= 1, "검색 임계값은 0~1이어야 합니다.");
        Assert.isTrue(llmModel != null && llmModel.length() <= 200
                && embeddingModel != null && embeddingModel.length() <= 200, "모델명은 200자 이내여야 합니다.");
    }
}
