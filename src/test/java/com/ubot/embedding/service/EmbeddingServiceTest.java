package com.ubot.embedding.service;

import com.sun.net.httpserver.HttpServer;
import com.ubot.embedding.client.EmbeddingClient;
import com.ubot.embedding.config.EmbeddingClientFactory;
import com.ubot.embedding.exception.EmbeddingErrorCode;
import com.ubot.embedding.exception.EmbeddingException;
import com.pgvector.PGvector;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EmbeddingServiceTest {

    private static final String MODEL_NAME = "bge-m3:567m";

    private HttpServer fakeOllamaServer;

    @AfterEach
    void tearDown() {
        if (fakeOllamaServer != null) {
            fakeOllamaServer.stop(0);
        }
    }

    private EmbeddingService createServiceWithFakeServer(
            HttpHandlerFunction handler, Duration readTimeout) throws IOException {

        fakeOllamaServer = HttpServer.create(new InetSocketAddress(0), 0);
        fakeOllamaServer.createContext("/api/embed", exchange -> {
            String requestBody = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            handler.handle(exchange, requestBody);
        });
        fakeOllamaServer.start();

        String baseUrl = "http://localhost:" + fakeOllamaServer.getAddress().getPort();
        return createService(baseUrl, Duration.ofSeconds(3), readTimeout);
    }

    private EmbeddingService createService(
            String baseUrl,
            Duration connectTimeout,
            Duration readTimeout) {

        var client = new EmbeddingClientFactory().createOllama(
                RestClient.builder(),
                baseUrl,
                MODEL_NAME,
                connectTimeout,
                readTimeout
        );

        return new EmbeddingService(client);
    }

    private static String vectorJson(double... head) {
        StringBuilder json = new StringBuilder("[");
        for (int index = 0; index < EmbeddingClient.DIMENSIONS; index++) {
            json.append(index == 0 ? "" : ", ").append(index < head.length ? head[index] : 0.0);
        }
        return json.append("]").toString();
    }

    private void respond(com.sun.net.httpserver.HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    @Test
    void embedText_정상_응답이면_PGvector를_반환한다() throws IOException {
        EmbeddingService service = createServiceWithFakeServer(
                (exchange, requestBody) -> respond(exchange, 200,
                        "{\"embeddings\": [" + vectorJson(0.1, 0.2, 0.3) + "]}"),
                Duration.ofSeconds(3));

        PGvector result = service.embedText("유심 재발급 어떻게 해요");

        assertThat(result).isNotNull();
        assertThat(result.toString()).contains("0.1").contains("0.2").contains("0.3");
    }

    @Test
    void embedText_요청에_설정된_모델명이_그대로_전송된다() throws IOException {
        AtomicReference<String> capturedRequestBody = new AtomicReference<>();

        EmbeddingService service = createServiceWithFakeServer(
                (exchange, requestBody) -> {
                    capturedRequestBody.set(requestBody);
                    respond(exchange, 200, "{\"embeddings\": [" + vectorJson(0.1) + "]}");
                },
                Duration.ofSeconds(3));

        service.embedText("테스트 질문");

        assertThat(capturedRequestBody.get()).contains("\"model\":\"" + MODEL_NAME + "\"");
    }

    @Test
    void embedTexts_여러_텍스트를_배치로_보내면_같은_개수의_벡터를_반환한다() throws IOException {
        EmbeddingService service = createServiceWithFakeServer(
                (exchange, requestBody) -> respond(exchange, 200,
                        "{\"embeddings\": [" + vectorJson(0.1, 0.2) + ", " + vectorJson(0.3, 0.4) + "]}"),
                Duration.ofSeconds(3));

        List<PGvector> results = service.embedTexts(List.of("질문1", "질문2"));

        assertThat(results).hasSize(2);
    }

    @Test
    void embeddings_필드가_없으면_EMBEDDING_RESPONSE_INVALID_예외를_던진다() throws IOException {
        EmbeddingService service = createServiceWithFakeServer(
                (exchange, requestBody) -> respond(exchange, 200, "{}"),
                Duration.ofSeconds(3));

        assertThatThrownBy(() -> service.embedText("테스트"))
                .isInstanceOf(EmbeddingException.class)
                .extracting(ex -> ((EmbeddingException) ex).getErrorCode())
                .isEqualTo(EmbeddingErrorCode.EMBEDDING_RESPONSE_INVALID);
    }

    @Test
    void embeddings가_빈_배열이면_EMBEDDING_RESPONSE_INVALID_예외를_던진다() throws IOException {
        EmbeddingService service = createServiceWithFakeServer(
                (exchange, requestBody) -> respond(exchange, 200, """
                        {"embeddings": []}
                        """),
                Duration.ofSeconds(3));

        assertThatThrownBy(() -> service.embedText("테스트"))
                .isInstanceOf(EmbeddingException.class)
                .extracting(ex -> ((EmbeddingException) ex).getErrorCode())
                .isEqualTo(EmbeddingErrorCode.EMBEDDING_RESPONSE_INVALID);
    }

    @Test
    void 서버가_5xx를_반환하면_EMBEDDING_SERVICE_UNAVAILABLE_예외를_던진다() throws IOException {
        EmbeddingService service = createServiceWithFakeServer(
                (exchange, requestBody) -> respond(exchange, 500, "{\"error\":\"internal\"}"),
                Duration.ofSeconds(3));

        assertThatThrownBy(() -> service.embedText("테스트"))
                .isInstanceOf(EmbeddingException.class)
                .extracting(ex -> ((EmbeddingException) ex).getErrorCode())
                .isEqualTo(EmbeddingErrorCode.EMBEDDING_SERVICE_UNAVAILABLE);
    }

    @Test
    void 연결_자체가_안_되면_EMBEDDING_TIMEOUT_예외를_던진다() {
        // 아무도 듣고 있지 않은 포트로 연결 시도 -> 즉시 연결 거부(refused) 발생
        EmbeddingService service = createService(
                "http://localhost:1", Duration.ofMillis(300), Duration.ofSeconds(1));

        assertThatThrownBy(() -> service.embedText("테스트"))
                .isInstanceOf(EmbeddingException.class)
                .extracting(ex -> ((EmbeddingException) ex).getErrorCode())
                .isEqualTo(EmbeddingErrorCode.EMBEDDING_TIMEOUT);
    }

    @Test
    void getModelName_선택된_구현체의_모델명을_반환한다() {
        EmbeddingService service = createService(
                "http://localhost:1", Duration.ofMillis(300), Duration.ofSeconds(1));

        assertThat(service.getModelName()).isEqualTo(MODEL_NAME);
    }

    @FunctionalInterface
    private interface HttpHandlerFunction {
        void handle(com.sun.net.httpserver.HttpExchange exchange, String requestBody) throws IOException;
    }
}