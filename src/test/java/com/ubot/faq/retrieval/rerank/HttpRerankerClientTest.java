package com.ubot.faq.retrieval.rerank;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

import com.sun.net.httpserver.HttpServer;
import com.ubot.faq.retrieval.FaqRetrievalConfig;
import com.ubot.faq.retrieval.FaqRetrievalProperties;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@DisplayName("Reranker HTTP 클라이언트")
class HttpRerankerClientTest {

    private static final JsonMapper JSON_MAPPER = JsonMapper.builder().build();

    private final List<JsonNode> requests = new CopyOnWriteArrayList<>();
    private final CountDownLatch released = new CountDownLatch(1);
    private HttpServer server;
    private volatile int status = 200;
    private volatile String body = "{}";
    private volatile boolean hang;

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/rerank", exchange -> {
            requests.add(JSON_MAPPER.readTree(exchange.getRequestBody().readAllBytes()));
            if (hang) {
                try {
                    released.await(5, TimeUnit.SECONDS);
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                }
            }
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
    }

    @AfterEach
    void stopServer() {
        released.countDown();
        server.stop(0);
        Thread.interrupted();
    }

    @Test
    @DisplayName("질문과 문서를 보내고 점수를 입력 순서로 돌려준다")
    void score_returnsScoresInInputOrder() {
        // 서버는 점수가 높은 순서로 응답합니다. index로 입력 자리를 찾아야 합니다.
        body = """
                {"results": [
                  {"index": 1, "relevance_score": 0.91},
                  {"index": 2, "relevance_score": 0.40},
                  {"index": 0, "relevance_score": -1.5}
                ]}
                """;

        List<Double> scores = client(Duration.ofSeconds(2))
                .score("가족 결합 할인", List.of("질문: A\n답변: a", "질문: B\n답변: b", "질문: C\n답변: c"));

        assertThat(scores).containsExactly(-1.5, 0.91, 0.40);
        assertThat(requests).hasSize(1);
        JsonNode request = requests.getFirst();
        assertThat(request.get("model").asString()).isEqualTo("ubot-reranker");
        assertThat(request.get("query").asString()).isEqualTo("가족 결합 할인");
        assertThat(request.get("documents")).hasSize(3);
        assertThat(request.get("documents").get(1).asString()).isEqualTo("질문: B\n답변: b");
    }

    @Test
    @DisplayName("서버가 5xx를 돌려주면 실패로 처리한다")
    void score_serverError_throws() {
        status = 503;
        body = "{\"error\":\"model loading\"}";

        assertThatThrownBy(() -> client(Duration.ofSeconds(2)).score("q", List.of("a")))
                .isInstanceOf(RerankException.class);
    }

    @Test
    @DisplayName("응답이 제한 시간을 넘으면 실패로 처리한다")
    void score_timeout_throws() {
        hang = true;

        assertThatThrownBy(() -> client(Duration.ofMillis(300)).score("q", List.of("a")))
                .isInstanceOf(RerankException.class);
    }

    @Test
    @DisplayName("점수가 빠진 문서가 있으면 실패로 처리한다")
    void score_missingResult_throws() {
        body = "{\"results\": [{\"index\": 0, \"relevance_score\": 0.5}]}";

        assertThatThrownBy(() -> client(Duration.ofSeconds(2)).score("q", List.of("a", "b")))
                .isInstanceOf(RerankException.class);
    }

    @Test
    @DisplayName("같은 index가 두 번 오면 실패로 처리한다")
    void score_duplicateIndex_throws() {
        body = """
                {"results": [
                  {"index": 0, "relevance_score": 0.5},
                  {"index": 0, "relevance_score": 0.4}
                ]}
                """;

        assertThatThrownBy(() -> client(Duration.ofSeconds(2)).score("q", List.of("a", "b")))
                .isInstanceOf(RerankException.class);
    }

    @Test
    @DisplayName("index가 범위를 벗어나면 실패로 처리한다")
    void score_indexOutOfRange_throws() {
        body = "{\"results\": [{\"index\": 3, \"relevance_score\": 0.5}]}";

        assertThatThrownBy(() -> client(Duration.ofSeconds(2)).score("q", List.of("a")))
                .isInstanceOf(RerankException.class);
    }

    @Test
    @DisplayName("점수가 숫자가 아니면 실패로 처리한다")
    void score_nonNumericScore_throws() {
        body = "{\"results\": [{\"index\": 0, \"relevance_score\": \"NaN\"}]}";

        assertThatThrownBy(() -> client(Duration.ofSeconds(2)).score("q", List.of("a")))
                .isInstanceOf(RerankException.class);
    }

    @Test
    @DisplayName("results가 없는 응답은 실패로 처리한다")
    void score_missingResults_throws() {
        body = "{\"data\": []}";

        assertThatThrownBy(() -> client(Duration.ofSeconds(2)).score("q", List.of("a")))
                .isInstanceOf(RerankException.class);
    }

    @Test
    @DisplayName("기다리는 중에 스레드가 중단되면 중단 표시를 남긴 채 실패를 알린다")
    void score_interrupted_keepsInterruptFlag() throws InterruptedException {
        hang = true;
        RerankerClient client = client(Duration.ofSeconds(5));
        Throwable[] thrown = new Throwable[1];
        boolean[] interrupted = new boolean[1];
        Thread worker = new Thread(() -> {
            try {
                client.score("q", List.of("a"));
            } catch (Throwable exception) {
                thrown[0] = exception;
                interrupted[0] = Thread.currentThread().isInterrupted();
            }
        });

        worker.start();
        while (requests.isEmpty()) {
            Thread.sleep(10);
        }
        worker.interrupt();
        worker.join(3000);

        assertThat(thrown[0]).isInstanceOf(RerankException.class);
        assertThat(interrupted[0]).isTrue();
    }

    /** 운영 코드와 같은 방법으로 클라이언트를 만듭니다. 제한 시간 설정까지 함께 확인합니다. */
    private RerankerClient client(Duration timeout) {
        FaqRetrievalProperties properties = new FaqRetrievalProperties();
        properties.getRerank().setBaseUrl("http://127.0.0.1:" + server.getAddress().getPort() + "/");
        properties.getRerank().setReadTimeout(timeout);
        return new FaqRetrievalConfig().rerankerClient(properties, RestClient.builder());
    }
}
