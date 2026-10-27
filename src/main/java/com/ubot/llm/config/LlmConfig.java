package com.ubot.llm.config;

import com.ubot.ai.config.AiRuntimeProperties;
import com.ubot.llm.client.LlmClient;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

@Configuration(proxyBeanMethods = false)
public class LlmConfig {

    @Bean
    public LlmClient llmClient(
            AiRuntimeProperties aiRuntimeProperties,
            RestClient.Builder restClientBuilder,

            @Value("${ollama.base-url:http://localhost:11435}")
            String ollamaBaseUrl,

            @Value("${spring.ai.ollama.chat.options.model:${OLLAMA_CHAT_MODEL:}}")
            String ollamaModelName,

            @Value("${LLM_BASE_URL:http://localhost:8000/v1}")
            String openAiCompatibleBaseUrl,

            @Value("${LLM_MODEL:ubot-chat}")
            String openAiCompatibleModelName,

            @Value("${LLM_CONNECT_TIMEOUT:3s}")
            Duration connectTimeout,

            @Value("${LLM_READ_TIMEOUT:120s}")
            Duration readTimeout) {

        var factory = new LlmClientFactory();

        return switch (aiRuntimeProperties.resolvedChatEngine()) {

            case OLLAMA -> factory.createOllama(
                    restClientBuilder,
                    ollamaBaseUrl,
                    ollamaModelName,
                    connectTimeout,
                    readTimeout
            );

            case VLLM -> factory.createOpenAiCompatible(
                    restClientBuilder,
                    openAiCompatibleBaseUrl,
                    openAiCompatibleModelName,
                    connectTimeout,
                    readTimeout
            );
        };
    }
}