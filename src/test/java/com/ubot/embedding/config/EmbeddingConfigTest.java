package com.ubot.embedding.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.ubot.ai.config.AiRuntimeConfig;
import com.ubot.embedding.client.EmbeddingClient;
import com.ubot.embedding.client.OllamaEmbeddingClient;
import com.ubot.embedding.client.OpenAiCompatibleEmbeddingClient;
import com.ubot.embedding.service.EmbeddingService;
import org.junit.jupiter.api.Test;
import org.springframework.boot.convert.ApplicationConversionService;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.web.client.RestClient;

class EmbeddingConfigTest {

    private final ApplicationContextRunner contextRunner =
            new ApplicationContextRunner()
                    .withInitializer(context -> context.getBeanFactory()
                            .setConversionService(
                                    ApplicationConversionService.getSharedInstance()
                            ))
                    .withUserConfiguration(
                            AiRuntimeConfig.class,
                            EmbeddingConfig.class,
                            EmbeddingService.class
                    )
                    .withBean(
                            RestClient.Builder.class,
                            RestClient::builder
                    );

    private final ApplicationContextRunner ollamaRunner =
            contextRunner.withPropertyValues(
                    "ollama.base-url=http://127.0.0.1:1",
                    "ollama.embedding.model=ollama-model"
            );

    @Test
    void selectsOllamaClientWhenAiModeIsOllama() {
        ollamaRunner
                .withPropertyValues("app.ai.mode=ollama")
                .run(context -> {
                    assertThat(context)
                            .hasNotFailed()
                            .hasSingleBean(EmbeddingClient.class);

                    assertThat(context.getBean(EmbeddingClient.class))
                            .isInstanceOf(OllamaEmbeddingClient.class);
                });
    }

    @Test
    void selectsOpenAiCompatibleClientWhenAiModeIsVllm() {
        contextRunner
                .withPropertyValues("app.ai.mode=vllm")
                .run(context -> {
                    assertThat(context)
                            .hasNotFailed()
                            .hasSingleBean(EmbeddingClient.class);

                    assertThat(context.getBean(EmbeddingClient.class))
                            .isInstanceOf(OpenAiCompatibleEmbeddingClient.class);
                });
    }

    @Test
    void reportsModelNameOfSelectedEngine() {
        var runner = ollamaRunner.withPropertyValues(
                "EMBEDDING_MODEL=served-model"
        );

        runner
                .withPropertyValues("app.ai.mode=ollama")
                .run(context ->
                        assertThat(
                                context.getBean(
                                        EmbeddingService.class
                                ).getModelName()
                        ).isEqualTo("ollama-model")
                );

        runner
                .withPropertyValues("app.ai.mode=vllm")
                .run(context ->
                        assertThat(
                                context.getBean(
                                        EmbeddingService.class
                                ).getModelName()
                        ).isEqualTo("served-model")
                );
    }

    @Test
    void usesServedModelNameAsDefaultForVllm() {
        contextRunner
                .withPropertyValues("app.ai.mode=vllm")
                .run(context ->
                        assertThat(
                                context.getBean(
                                        EmbeddingService.class
                                ).getModelName()
                        ).isEqualTo("ubot-embedding")
                );
    }

    @Test
    void rejectsUnlimitedTimeoutForVllm() {
        contextRunner
                .withPropertyValues(
                        "app.ai.mode=vllm",
                        "EMBEDDING_READ_TIMEOUT=0s"
                )
                .run(context -> {
                    assertThat(context).hasFailed();

                    assertThat(context.getStartupFailure())
                            .hasRootCauseInstanceOf(
                                    IllegalArgumentException.class
                            );
                });
    }

    @Test
    void customModeUsesConfiguredEmbeddingEngine() {
        ollamaRunner
                .withPropertyValues(
                        "app.ai.mode=custom",
                        "app.ai.chat-engine=vllm",
                        "app.ai.embedding-engine=ollama"
                )
                .run(context -> {
                    assertThat(context)
                            .hasNotFailed()
                            .hasSingleBean(EmbeddingClient.class);

                    assertThat(context.getBean(EmbeddingClient.class))
                            .isInstanceOf(OllamaEmbeddingClient.class);
                });
    }
}