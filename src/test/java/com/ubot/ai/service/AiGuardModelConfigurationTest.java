package com.ubot.ai.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ubot.ai.config.AiRuntimeConfig;
import com.ubot.llm.config.LlmConfig;
import com.ubot.llm.dto.request.LlmMessageRequestDto;
import com.ubot.llm.dto.request.LlmRequestDto;
import com.ubot.llm.enums.LlmMessageRole;
import com.ubot.llm.exception.LlmErrorCode;
import com.ubot.llm.exception.LlmException;
import com.ubot.llm.service.LlmService;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.boot.convert.ApplicationConversionService;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.http.HttpRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.mock.http.client.MockClientHttpResponse;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** 모든 HTTP 응답을 메모리에서 반환합니다. 실제 서버 연결·모델 다운로드는 하지 않습니다. */
class AiGuardModelConfigurationTest {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private final List<SentRequest> requests = new ArrayList<>();
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withInitializer(context -> context.getBeanFactory()
                    .setConversionService(ApplicationConversionService.getSharedInstance()))
            .withUserConfiguration(AiRuntimeConfig.class, LlmConfig.class, LlmService.class, AiGuardModelService.class)
            .withBean(MeterRegistry.class, SimpleMeterRegistry::new)
            .withBean(RestClient.Builder.class, () -> RestClient.builder().requestInterceptor(this::respond))
            .withPropertyValues("LLM_BASE_URL=http://chat-vllm.invalid:8000/v1", "LLM_MODEL=ubot-chat",
                    "ollama.base-url=http://chat-ollama.invalid:11435", "OLLAMA_CHAT_MODEL=local-chat",
                    "AI_GUARD_ENGINE=", "AI_GUARD_BASE_URL=", "AI_GUARD_MODEL=",
                    "LLM_CONNECT_TIMEOUT=1s", "LLM_READ_TIMEOUT=3s");

    @ParameterizedTest
    @CsvSource({"vllm,ubot-chat,http://chat-vllm.invalid:8000/v1/chat/completions",
            "ollama,local-chat,http://chat-ollama.invalid:11435/api/chat"})
    void defaultsUseTheSelectedChatEngineAndItsApiModelName(String engine, String model, String url) {
        runner.withPropertyValues("app.ai.mode=" + engine).run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(ReflectionTestUtils.getField(context.getBean(AiGuardModelService.class), "llm"))
                    .isSameAs(context.getBean(LlmService.class));
            assertThat(ReflectionTestUtils.getField(context.getBean(AiGuardModelService.class), "guardClient"))
                    .isNull();
            assertThat(context.getBean(AiGuardModelService.class).generate(request()).answer()).isEqualTo("모의 판정");
            assertRequest(0, url, model);
            assertThat(requests).hasSize(1);
            if (engine.equals("ollama")) {
                assertThat(requests.getFirst().body().get("format").get("type").asString()).isEqualTo("object");
            } else {
                assertThat(requests.getFirst().body().get("response_format").get("json_schema").get("name").asString())
                        .isEqualTo("input_safety_and_planning");
            }
        });
    }

    @Test
    void inheritsACustomServedModelNameInsteadOfTheCheckpointName() {
        runner.withPropertyValues("app.ai.mode=vllm", "LLM_MODEL=team-chat-alias").run(context -> {
            context.getBean(AiGuardModelService.class).generate(request());
            assertRequest(0, "http://chat-vllm.invalid:8000/v1/chat/completions", "team-chat-alias");
        });
    }

    @Test
    void explicitGuardSettingsDoNotChangeAnswerGenerationSettings() {
        runner.withPropertyValues("app.ai.mode=vllm", "AI_GUARD_BASE_URL=http://guard.invalid/v1/",
                "AI_GUARD_MODEL=guard-alias").run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(ReflectionTestUtils.getField(context.getBean(AiGuardModelService.class), "llm"))
                    .isNotSameAs(context.getBean(LlmService.class));
            context.getBean(AiGuardModelService.class).generate(request());
            context.getBean(LlmService.class).generateAnswer(new LlmRequestDto(request().messages()));
            assertThat(requests).hasSize(2);
            assertRequest(0, "http://guard.invalid/v1/chat/completions", "guard-alias");
            assertRequest(1, "http://chat-vllm.invalid:8000/v1/chat/completions", "ubot-chat");
        });
    }

    @Test
    void aDifferentProtocolOnTheSameServerStillSharesTheChatAdmissionLimit() {
        runner.withPropertyValues("app.ai.mode=vllm", "AI_GUARD_ENGINE=ollama",
                "AI_GUARD_BASE_URL=http://chat-vllm.invalid:8000", "AI_GUARD_MODEL=local-guard")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    var guard = context.getBean(AiGuardModelService.class);
                    assertThat(ReflectionTestUtils.getField(guard, "llm"))
                            .isSameAs(context.getBean(LlmService.class));
                    guard.generate(request());
                    assertRequest(0, "http://chat-vllm.invalid:8000/api/chat", "local-guard");
                });
    }

    @ParameterizedTest
    @CsvSource({"ollama,vllm,http://guard.invalid/v1,http://guard.invalid/v1/chat/completions,local-chat",
            "vllm,ollama,http://guard.invalid:11434,http://guard.invalid:11434/api/chat,ubot-chat"})
    void aSeparateGuardCanSelectADifferentEngine(String chatEngine, String guardEngine, String baseUrl,
            String requestUrl, String chatModel) {
        runner.withPropertyValues("app.ai.mode=" + chatEngine, "AI_GUARD_ENGINE=" + guardEngine,
                "AI_GUARD_BASE_URL=" + baseUrl, "AI_GUARD_MODEL=guard-only").run(context -> {
            assertThat(context).hasNotFailed();
            context.getBean(AiGuardModelService.class).generate(request());
            assertRequest(0, requestUrl, "guard-only");
            assertThat(context.getBean(LlmService.class).getModelName()).isEqualTo(chatModel);
        });
    }

    @Test
    void customModeInheritsTheChatEngineRatherThanTheEmbeddingEngine() {
        runner.withPropertyValues("app.ai.mode=custom", "app.ai.chat-engine=ollama",
                "app.ai.embedding-engine=vllm").run(context -> {
            assertThat(context).hasNotFailed();
            context.getBean(AiGuardModelService.class).generate(request());
            assertRequest(0, "http://chat-ollama.invalid:11435/api/chat", "local-chat");
        });
    }

    @Test
    void anUnconfiguredLocalModelDoesNotFallBackToTheServerOrTheCheckpoint() {
        runner.withPropertyValues("app.ai.mode=ollama", "OLLAMA_CHAT_MODEL=").run(context -> {
            assertThat(context).hasNotFailed();
            assertThatThrownBy(() -> context.getBean(AiGuardModelService.class).generate(request()))
                    .isInstanceOfSatisfying(LlmException.class, error ->
                            assertThat(error.getErrorCode()).isEqualTo(LlmErrorCode.LLM_MODEL_NOT_CONFIGURED));
            assertThat(requests).isEmpty();
        });
    }

    @Test
    void anUnsupportedGuardEngineFailsConfigurationInsteadOfSilentlySelectingAnotherEngine() {
        runner.withPropertyValues("app.ai.mode=vllm", "AI_GUARD_ENGINE=unknown").run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure()).hasRootCauseInstanceOf(IllegalArgumentException.class);
            assertThat(requests).isEmpty();
        });
    }

    private void assertRequest(int index, String url, String model) {
        assertThat(requests.get(index).uri().toString()).isEqualTo(url);
        assertThat(requests.get(index).body().get("model").asString()).isEqualTo(model);
    }

    private ClientHttpResponse respond(HttpRequest request, byte[] body, ClientHttpRequestExecution ignored)
            throws IOException {
        requests.add(new SentRequest(request.getURI(), JSON.readTree(body)));
        String json;
        if (request.getURI().getPath().equals("/api/chat")) {
            json = "{\"model\":\"mock\",\"message\":{\"role\":\"assistant\",\"content\":\"모의 판정\"},"
                    + "\"done\":true,\"done_reason\":\"stop\"}";
        } else {
            assertThat(request.getURI().getPath()).isEqualTo("/v1/chat/completions");
            json = "{\"choices\":[{\"finish_reason\":\"stop\","
                    + "\"message\":{\"role\":\"assistant\",\"content\":\"모의 판정\"}}]}";
        }
        var response = new MockClientHttpResponse(json.getBytes(StandardCharsets.UTF_8), HttpStatus.OK);
        response.getHeaders().setContentType(MediaType.APPLICATION_JSON);
        return response;
    }

    private LlmRequestDto request() {
        return new LlmRequestDto(List.of(new LlmMessageRequestDto(LlmMessageRole.USER, "검사 자료")))
                .withResponseSchema(Map.of("type", "object"))
                .withGenerationOptions("input_safety_and_planning", 512, true);
    }

    private record SentRequest(URI uri, JsonNode body) { }
}
