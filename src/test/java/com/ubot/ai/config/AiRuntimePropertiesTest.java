package com.ubot.ai.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class AiRuntimePropertiesTest {

    private final ApplicationContextRunner contextRunner =
            new ApplicationContextRunner()
                    .withUserConfiguration(AiRuntimeConfig.class);

    @Test
    void ollamaModeUsesOllamaForBoth() {
        contextRunner
                .withPropertyValues("app.ai.mode=ollama")
                .run(context -> {
                    assertThat(context).hasNotFailed();

                    AiRuntimeProperties properties =
                            context.getBean(AiRuntimeProperties.class);

                    assertThat(properties.resolvedChatEngine())
                            .isEqualTo(AiEngine.OLLAMA);

                    assertThat(properties.resolvedEmbeddingEngine())
                            .isEqualTo(AiEngine.OLLAMA);
                });
    }

    @Test
    void vllmModeUsesVllmForBoth() {
        contextRunner
                .withPropertyValues("app.ai.mode=vllm")
                .run(context -> {
                    assertThat(context).hasNotFailed();

                    AiRuntimeProperties properties =
                            context.getBean(AiRuntimeProperties.class);

                    assertThat(properties.resolvedChatEngine())
                            .isEqualTo(AiEngine.VLLM);

                    assertThat(properties.resolvedEmbeddingEngine())
                            .isEqualTo(AiEngine.VLLM);
                });
    }

    @Test
    void customModeCanUseDifferentEngines() {
        contextRunner
                .withPropertyValues(
                        "app.ai.mode=custom",
                        "app.ai.chat-engine=vllm",
                        "app.ai.embedding-engine=ollama"
                )
                .run(context -> {
                    assertThat(context).hasNotFailed();

                    AiRuntimeProperties properties =
                            context.getBean(AiRuntimeProperties.class);

                    assertThat(properties.resolvedChatEngine())
                            .isEqualTo(AiEngine.VLLM);

                    assertThat(properties.resolvedEmbeddingEngine())
                            .isEqualTo(AiEngine.OLLAMA);
                });
    }

    @Test
    void customModeRequiresBothEngines() {
        contextRunner
                .withPropertyValues(
                        "app.ai.mode=custom",
                        "app.ai.chat-engine=vllm"
                )
                .run(context ->
                        assertThat(context).hasFailed()
                );
    }

    @Test
    void normalModeRejectsCustomOverrides() {
        contextRunner
                .withPropertyValues(
                        "app.ai.mode=ollama",
                        "app.ai.chat-engine=vllm"
                )
                .run(context ->
                        assertThat(context).hasFailed()
                );
    }

    @Test
    void rejectsUnknownMode() {
        contextRunner
                .withPropertyValues("app.ai.mode=whatever")
                .run(context ->
                        assertThat(context).hasFailed()
                );
    }
}
