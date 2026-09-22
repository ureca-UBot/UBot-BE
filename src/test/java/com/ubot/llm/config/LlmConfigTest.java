package com.ubot.llm.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpServer;
import com.ubot.llm.dto.request.LlmMessageRequestDto;
import com.ubot.llm.dto.request.LlmRequestDto;
import com.ubot.llm.enums.LlmErrorCode;
import com.ubot.llm.enums.LlmMessageRole;
import com.ubot.llm.exception.LlmException;
import com.ubot.llm.service.LlmService;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.convert.ApplicationConversionService;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.json.JsonMapper;

class LlmConfigTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withInitializer(context -> context.getBeanFactory()
                    .setConversionService(ApplicationConversionService.getSharedInstance()))
            .withUserConfiguration(LlmConfig.class, LlmService.class)
            .withBean(RestClient.Builder.class, RestClient::builder)
            .withPropertyValues("OLLAMA_CHAT_MODEL=", "LLM_CONNECT_TIMEOUT=1s", "LLM_READ_TIMEOUT=3s",
                    "spring.ai.ollama.base-url=http://127.0.0.1:1");

    @Test
    void startsWithoutModelOrServerButRejectsGeneration() {
        contextRunner.run(context -> {
            assertThat(context).hasNotFailed().hasSingleBean(LlmService.class);
            // 기존 Spring AI ChatModel 빈의 선택을 바꾸지 않습니다.
            assertThat(context).doesNotHaveBean(ChatModel.class);
            assertThatThrownBy(() -> context.getBean(LlmService.class).generateAnswer(question()))
                    .isInstanceOfSatisfying(LlmException.class, exception -> assertThat(exception.getErrorCode())
                            .isEqualTo(LlmErrorCode.LLM_MODEL_NOT_CONFIGURED));
        });
    }

    @Test
    void rejectsUnlimitedTimeoutConfiguration() {
        contextRunner.withPropertyValues("LLM_READ_TIMEOUT=0s").run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure()).hasRootCauseInstanceOf(IllegalArgumentException.class);
        });
    }

    @Test
    void readsModelFromExistingEnvironmentVariable() throws IOException {
        var requestBody = new AtomicReference<String>();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/chat", exchange -> {
            requestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] response = """
                    {"model":"environment-model","message":{"role":"assistant","content":"답변"},
                     "done":true,"done_reason":"stop"}
                    """.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();

        try {
            contextRunner.withPropertyValues("OLLAMA_CHAT_MODEL=environment-model",
                    "spring.ai.ollama.base-url=http://127.0.0.1:" + server.getAddress().getPort())
                    .run(context -> {
                        assertThat(context).hasNotFailed();
                        assertThat(context.getBean(LlmService.class).generateAnswer(question()).answer())
                                .isEqualTo("답변");
                        var body = JsonMapper.builder().build().readTree(requestBody.get());
                        assertThat(body.get("model").asString()).isEqualTo("environment-model");
                    });
        } finally {
            server.stop(0);
        }
    }

    private LlmRequestDto question() {
        return new LlmRequestDto(List.of(new LlmMessageRequestDto(LlmMessageRole.USER, "질문")));
    }
}
