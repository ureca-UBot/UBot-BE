package com.ubot.llm.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import com.ubot.llm.config.LlmConfig;
import com.ubot.llm.dto.request.LlmMessageRequestDto;
import com.ubot.llm.dto.request.LlmRequestDto;
import com.ubot.llm.enums.LlmErrorCode;
import com.ubot.llm.enums.LlmMessageRole;
import com.ubot.llm.exception.LlmException;
import com.ubot.llm.service.LlmService;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.json.JsonMapper;

class OllamaClientHttpTest {

    private static final String MODEL_NAME = "configured-chat-model";
    private static final String RESPONSE = """
            {"model":"configured-chat-model","created_at":"2026-09-21T00:00:00Z",
             "message":{"role":"assistant","content":"가까운 매장에서 재발급할 수 있습니다.",
                        "thinking":"이 내용은 반환하지 않습니다."},
             "done":true,"done_reason":"stop","prompt_eval_count":12,"eval_count":8}
            """;

    private HttpServer server;

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void sendsConfiguredModelAndOrderedMessagesToChatEndpoint() throws IOException {
        var capturedBody = new AtomicReference<String>();
        var service = createService(exchange -> {
            capturedBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            respond(exchange, 200, RESPONSE);
        }, Duration.ofSeconds(3));
        var request = new LlmRequestDto(List.of(
                new LlmMessageRequestDto(LlmMessageRole.SYSTEM, "제공된 FAQ만 참고하세요."),
                new LlmMessageRequestDto(LlmMessageRole.USER, "이전 질문"),
                new LlmMessageRequestDto(LlmMessageRole.ASSISTANT, "이전 답변"),
                new LlmMessageRequestDto(LlmMessageRole.USER,
                        "질문: 유심 재발급 방법\nFAQ 12: 유심 재발급은 가까운 매장에서 가능합니다.")));

        var result = service.generateAnswer(request);

        var body = JsonMapper.builder().build().readTree(capturedBody.get());
        assertThat(body.get("model").asString()).isEqualTo(MODEL_NAME);
        assertThat(body.get("stream").asBoolean()).isFalse();
        assertThat(body.get("messages").size()).isEqualTo(4);
        assertThat(body.get("messages").get(0).get("role").asString()).isEqualTo("system");
        assertThat(body.get("messages").get(2).get("role").asString()).isEqualTo("assistant");
        assertThat(body.get("messages").get(3).get("content").asString())
                .isEqualTo(request.messages().getLast().content());
        assertThat(result.answer()).isEqualTo("가까운 매장에서 재발급할 수 있습니다.");
    }

    @Test
    void doesNotReusePreviousRequestAsChatHistory() throws IOException {
        var capturedBody = new AtomicReference<String>();
        var service = createService(exchange -> {
            capturedBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            respond(exchange, 200, RESPONSE);
        }, Duration.ofSeconds(3));

        service.generateAnswer(question("첫 번째 사용자 질문"));
        service.generateAnswer(question("다른 사용자 질문"));

        var body = JsonMapper.builder().build().readTree(capturedBody.get());
        assertThat(body.get("messages").size()).isEqualTo(1);
        assertThat(body.get("messages").get(0).get("content").asString()).isEqualTo("다른 사용자 질문");
    }

    @Test
    void reportsServerFailureWithoutRetrying() throws IOException {
        var calls = new AtomicInteger();
        var service = createService(exchange -> {
            calls.incrementAndGet();
            respond(exchange, 503, "{\"error\":\"unavailable\"}");
        }, Duration.ofSeconds(3));

        assertFailure(service, LlmErrorCode.LLM_SERVICE_UNAVAILABLE);
        assertThat(calls.get()).isEqualTo(1);
    }

    @Test
    void reportsMalformedJson() throws IOException {
        var service = createService(exchange -> respond(exchange, 200, "not-json"), Duration.ofSeconds(3));

        assertFailure(service, LlmErrorCode.LLM_RESPONSE_INVALID);
    }

    @Test
    void enforcesReadTimeout() throws IOException {
        var releaseResponse = new CountDownLatch(1);
        var service = createService(exchange -> {
            try {
                releaseResponse.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            } finally {
                exchange.close();
            }
        }, Duration.ofMillis(150));

        try {
            assertFailure(service, LlmErrorCode.LLM_TIMEOUT);
        } finally {
            releaseResponse.countDown();
        }
    }

    private LlmService createService(HttpHandler handler, Duration readTimeout) throws IOException {
        // 외부 Ollama나 개발 DB 없이 Spring AI의 실제 HTTP 요청을 검증합니다.
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/chat", handler);
        server.start();
        String baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
        var client = new LlmConfig().llmClient(RestClient.builder(), baseUrl, MODEL_NAME,
                Duration.ofSeconds(1), readTimeout);
        return new LlmService(client);
    }

    private LlmRequestDto question(String content) {
        return new LlmRequestDto(List.of(new LlmMessageRequestDto(LlmMessageRole.USER, content)));
    }

    private void assertFailure(LlmService service, LlmErrorCode expected) {
        assertThatThrownBy(() -> service.generateAnswer(question("테스트 질문")))
                .isInstanceOfSatisfying(LlmException.class,
                        exception -> assertThat(exception.getErrorCode()).isEqualTo(expected));
    }

    private void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }
}
