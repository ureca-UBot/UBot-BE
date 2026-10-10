package com.ubot.embedding.config;

import com.ubot.ai.config.AiRuntimeProperties;
import com.ubot.embedding.client.EmbeddingClient;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

@Configuration(proxyBeanMethods = false)
public class EmbeddingConfig {

    @Bean
    public EmbeddingClient embeddingClient(
            AiRuntimeProperties aiRuntimeProperties,
            RestClient.Builder restClientBuilder,

            @Value("${ollama.base-url:http://localhost:11435}")
            String ollamaBaseUrl,

            @Value("${ollama.embedding.model:bge-m3:567m}")
            String ollamaModelName,

            @Value("${ollama.connect-timeout:3s}")
            Duration ollamaConnectTimeout,

            @Value("${ollama.read-timeout:10s}")
            Duration ollamaReadTimeout,

            @Value("${EMBEDDING_BASE_URL:http://localhost:8001/v1}")
            String openAiCompatibleBaseUrl,

            @Value("${EMBEDDING_MODEL:ubot-embedding}")
            String openAiCompatibleModelName,

            @Value("${EMBEDDING_CONNECT_TIMEOUT:3s}")
            Duration openAiCompatibleConnectTimeout,

            @Value("${EMBEDDING_READ_TIMEOUT:30s}")
            Duration openAiCompatibleReadTimeout) {

        var factory = new EmbeddingClientFactory();

        return switch (aiRuntimeProperties.resolvedEmbeddingEngine()) {

            case OLLAMA -> factory.createOllama(
                    restClientBuilder,
                    ollamaBaseUrl,
                    ollamaModelName,
                    ollamaConnectTimeout,
                    ollamaReadTimeout
            );

            case VLLM -> factory.createOpenAiCompatible(
                    restClientBuilder,
                    openAiCompatibleBaseUrl,
                    openAiCompatibleModelName,
                    openAiCompatibleConnectTimeout,
                    openAiCompatibleReadTimeout
            );
        };
    }
}