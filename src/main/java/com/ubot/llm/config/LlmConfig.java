package com.ubot.llm.config;

import com.ubot.llm.client.LlmClient;
import com.ubot.llm.client.OllamaClient;
import com.ubot.llm.client.VllmClient;
import java.net.http.HttpClient;
import java.time.Duration;
import org.springframework.ai.ollama.OllamaChatModel;
import org.springframework.ai.ollama.api.OllamaApi;
import org.springframework.ai.ollama.api.OllamaChatOptions;
import org.springframework.ai.ollama.management.ModelManagementOptions;
import org.springframework.ai.ollama.management.PullModelStrategy;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.retry.RetryPolicy;
import org.springframework.core.retry.RetryTemplate;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.util.Assert;
import org.springframework.web.client.RestClient;

/** LLM_PROVIDER 값에 따라 LlmClient 구현체 하나만 빈으로 등록합니다. 값이 없으면 Ollama를 씁니다. */
@Configuration(proxyBeanMethods = false)
public class LlmConfig {

    @Bean
    @ConditionalOnProperty(name = "LLM_PROVIDER", havingValue = "ollama", matchIfMissing = true)
    public LlmClient llmClient(
            RestClient.Builder restClientBuilder,
            @Value("${spring.ai.ollama.base-url:http://localhost:11435}") String baseUrl,
            @Value("${spring.ai.ollama.chat.options.model:${OLLAMA_CHAT_MODEL:}}") String modelName,
            @Value("${LLM_CONNECT_TIMEOUT:3s}") Duration connectTimeout,
            @Value("${LLM_READ_TIMEOUT:120s}") Duration readTimeout) {
        Assert.isTrue(connectTimeout.isPositive(), "LLM 연결 제한 시간은 0보다 커야 합니다.");
        Assert.isTrue(readTimeout.isPositive(), "LLM 응답 제한 시간은 0보다 커야 합니다.");

        HttpClient httpClient = HttpClient.newBuilder().connectTimeout(connectTimeout).build();
        var requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(readTimeout);

        // 임베딩용 HTTP 클라이언트와 Spring AI 자동 구성 빈을 변경하지 않습니다.
        var ollamaApi = OllamaApi.builder()
                .baseUrl(baseUrl)
                .restClientBuilder(restClientBuilder.clone().requestFactory(requestFactory))
                .build();
        var chatModel = OllamaChatModel.builder()
                .ollamaApi(ollamaApi)
                .options(OllamaChatOptions.builder().model(modelName).build())
                .modelManagementOptions(ModelManagementOptions.builder()
                        .pullModelStrategy(PullModelStrategy.NEVER).build())
                // 재시도 정책은 AI 흐름 담당자가 결정합니다. 여기서는 한 번만 요청합니다.
                .retryTemplate(new RetryTemplate(RetryPolicy.builder().maxRetries(0).build()))
                .build();
        return new OllamaClient(chatModel, modelName);
    }

    @Bean
    @ConditionalOnProperty(name = "LLM_PROVIDER", havingValue = "vllm")
    public LlmClient vllmClient(
            RestClient.Builder restClientBuilder,
            @Value("${VLLM_BASE_URL:http://localhost:8000/v1}") String baseUrl,
            @Value("${VLLM_MODEL:ubot-chat}") String modelName,
            @Value("${LLM_CONNECT_TIMEOUT:3s}") Duration connectTimeout,
            @Value("${LLM_READ_TIMEOUT:120s}") Duration readTimeout) {
        Assert.isTrue(connectTimeout.isPositive(), "LLM 연결 제한 시간은 0보다 커야 합니다.");
        Assert.isTrue(readTimeout.isPositive(), "LLM 응답 제한 시간은 0보다 커야 합니다.");

        // vLLM 서버(uvicorn)는 HTTP/2 업그레이드 요청을 지원하지 않으므로 HTTP/1.1로 고정합니다.
        HttpClient httpClient = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(connectTimeout)
                .build();
        var requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(readTimeout);

        // 다른 HTTP 클라이언트가 쓰는 공용 빌더는 바꾸지 않습니다.
        RestClient restClient = restClientBuilder.clone()
                .baseUrl(baseUrl.replaceAll("/+$", ""))
                .requestFactory(requestFactory)
                .build();
        return new VllmClient(restClient, modelName);
    }
}
