package com.ubot.ai.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ubot.ai.config.AiRuntimeProperties;
import com.ubot.llm.client.LlmClient;
import com.ubot.llm.dto.request.LlmMessageRequestDto;
import com.ubot.llm.dto.request.LlmRequestDto;
import com.ubot.llm.dto.response.LlmResponseDto;
import com.ubot.llm.enums.LlmMessageRole;
import com.ubot.llm.exception.LlmErrorCode;
import com.ubot.llm.exception.LlmException;
import com.ubot.llm.service.LlmService;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.io.UncheckedIOException;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.mock.http.client.MockClientHttpResponse;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** 모의 응답과 실제 Semaphore만 사용합니다. 외부 모델·HTTP 서버는 호출하지 않습니다. */
class AiGuardPermitSharingTest {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private final SimpleMeterRegistry metrics = new SimpleMeterRegistry();
    private final List<JsonNode> sent = new CopyOnWriteArrayList<>();

    @ParameterizedTest
    @CsvSource({
            "vllm,http://chat.invalid:8000/v1,,",
            "ollama,http://chat.invalid:11435,,",
            "vllm,http://chat.invalid:8000/v1,http://CHAT.invalid:8000/v1/,guard-alias",
            "vllm,http://chat.invalid/v1,http://chat.invalid:80/other-api,guard-alias",
            "vllm,https://chat.invalid/v1,https://CHAT.invalid:443/v1/,guard-alias",
            "ollama,http://localhost:11435,http://127.0.0.1:11435,guard-alias",
            "vllm,http://localhost:8000/v1,http://[::1]:8000/v1,guard-alias"
    })
    void anInterruptedButStillRunningAnswerKeepsTheSharedServerPermit(String engine, String chatUrl,
            String guardUrl, String guardAlias) throws Exception {
        var server = new SimulatedModel();
        server.answerGate = new Gate();
        var chat = new LlmService(server, 1, metrics);
        // 별도 가드 상한을 크게 지정해도 같은 서버의 기존 상한 1을 늘릴 수 없습니다.
        var guard = guard(chat, server, engine, chatUrl, guardUrl, guardAlias, 64, Duration.ofMillis(20));
        var answerFailure = new AtomicReference<Throwable>();
        Thread answer = start(() -> chat.generateAnswer(request("answer")), answerFailure);
        try {
            server.answerGate.awaitStarted();
            answer.interrupt();
            assertThat(server.answerGate.interrupted.await(5, TimeUnit.SECONDS)).isTrue();
            assertBusy(guard);
            assertThat(answer.isAlive()).isTrue();
            assertThat(server.guardCalls.get()).isZero();
            assertThat(sent).isEmpty();
            assertThat(metrics.get("llm.concurrency.in.use").gauge().value()).isEqualTo(1);

            server.answerGate.release.countDown();
            join(answer);
            assertThat(answerFailure.get()).isNull();
            assertThat(guard.generate(request("guard")).answer()).isEqualTo("모의 결과");
            assertThat(server.guardCalls.get()).isEqualTo(1);
            assertThat(server.peak.get()).isEqualTo(1);
            assertThat(metrics.get("llm.concurrency.in.use").gauge().value()).isZero();
            if (guardAlias != null) {
                assertThat(sent).hasSize(1);
                assertThat(sent.getFirst().get("model").asString()).isEqualTo(guardAlias);
            }
        } finally {
            server.answerGate.release.countDown();
            join(answer);
        }
    }

    @ParameterizedTest
    @CsvSource({"http://guard.invalid:8000/v1", "http://chat.invalid:8001/v1"})
    void aSeparateServerHasItsOwnBoundedPermits(String guardUrl) throws Exception {
        var answerServer = new SimulatedModel();
        var guardServer = new SimulatedModel();
        answerServer.answerGate = new Gate();
        var chat = new LlmService(answerServer, 1, metrics);
        var guard = guard(chat, guardServer, "vllm", "http://chat.invalid:8000/v1", guardUrl,
                "guard-alias", 1, Duration.ofMillis(20));
        var answerFailure = new AtomicReference<Throwable>();
        var guardFailure = new AtomicReference<Throwable>();
        Thread answer = start(() -> chat.generateAnswer(request("answer")), answerFailure);
        Thread analysis = null;
        try {
            answerServer.answerGate.awaitStarted();
            assertThat(guard.generate(request("guard")).answer()).isEqualTo("모의 결과");
            assertThat(answer.isAlive()).isTrue();
            guardServer.guardGate = new Gate();
            analysis = start(() -> guard.generate(request("guard")), guardFailure);
            guardServer.guardGate.awaitStarted();
            assertBusy(guard);
            assertThat(guardServer.guardCalls.get()).isEqualTo(2);
            assertThat(guardServer.peak.get()).isEqualTo(1);
        } finally {
            if (guardServer.guardGate != null) guardServer.guardGate.release.countDown();
            answerServer.answerGate.release.countDown();
            if (analysis != null) join(analysis);
            join(answer);
        }
        assertThat(answerFailure.get()).isNull();
        assertThat(guardFailure.get()).isNull();
    }

    @Test
    void aSharedGuardFailureReturnsThePermitWithoutRetryingOrChangingTheAnswerClient() {
        var server = new SimulatedModel();
        var chat = new LlmService(server, 1, metrics);
        var guard = guard(chat, server, "vllm", "http://chat.invalid:8000/v1", null,
                "guard-alias", 64, Duration.ofMillis(20));
        var failure = new UncheckedIOException(new SocketTimeoutException("synthetic timeout"));
        server.guardFailure.set(failure);
        assertThatThrownBy(() -> guard.generate(request("guard")))
                .isInstanceOfSatisfying(LlmException.class,
                        error -> assertThat(error.getErrorCode()).isEqualTo(LlmErrorCode.LLM_TIMEOUT));
        assertThat(server.guardCalls.get()).isEqualTo(1);
        assertThat(metrics.get("llm.concurrency.in.use").gauge().value()).isZero();
        assertThat(chat.generateAnswer(request("answer")).answer()).isEqualTo("모의 결과");
        assertThat(chat.getModelName()).isEqualTo("chat-alias");
        assertThat(guard.generate(request("guard")).answer()).isEqualTo("모의 결과");
        assertThat(sent).hasSize(2);
        assertThat(metrics.get("llm.requests").tag("kind", "judgment").tag("outcome", "timeout")
                .timer().count()).isEqualTo(1);
    }

    @Test
    void aWaitingSharedGuardCanBeCancelledWithoutCallingTheModelOrReleasingAnothersPermit() throws Exception {
        var server = new SimulatedModel();
        server.answerGate = new Gate();
        var chat = new LlmService(server, 1, metrics);
        var guard = guard(chat, server, "vllm", "http://chat.invalid:8000/v1", null,
                "guard-alias", 64, Duration.ofSeconds(5));
        var answerFailure = new AtomicReference<Throwable>();
        var guardFailure = new AtomicReference<Throwable>();
        Thread answer = start(() -> chat.generateAnswer(request("answer")), answerFailure);
        Thread analysis = null;
        try {
            server.answerGate.awaitStarted();
            analysis = start(() -> guard.generate(request("guard")), guardFailure);
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (metrics.get("llm.concurrency.waiting").gauge().value() == 0
                    && System.nanoTime() < deadline) Thread.sleep(5);
            assertThat(metrics.get("llm.concurrency.waiting").gauge().value()).isEqualTo(1);
            analysis.interrupt();
            join(analysis);
            assertThat(guardFailure.get()).isInstanceOf(CancellationException.class);
            assertThat(sent).isEmpty();
            assertThat(server.guardCalls.get()).isZero();
            assertThat(metrics.get("llm.concurrency.in.use").gauge().value()).isEqualTo(1);
        } finally {
            if (analysis != null) analysis.interrupt();
            server.answerGate.release.countDown();
            if (analysis != null) join(analysis);
            join(answer);
        }
        assertThat(answerFailure.get()).isNull();
        assertThat(guard.generate(request("guard")).answer()).isEqualTo("모의 결과");
    }

    private AiGuardModelService guard(LlmService chat, SimulatedModel model, String engine, String chatUrl,
            String guardUrl, String alias, int maxConcurrent, Duration permitWait) {
        var runtime = new AiRuntimeProperties();
        runtime.setMode(engine);
        var environment = new MockEnvironment().withProperty("LLM_BASE_URL", chatUrl)
                .withProperty("ollama.base-url", chatUrl).withProperty("LLM_MODEL", "chat-alias")
                .withProperty("OLLAMA_CHAT_MODEL", "chat-alias");
        if (guardUrl != null) environment.withProperty("AI_GUARD_BASE_URL", guardUrl);
        if (alias != null) environment.withProperty("AI_GUARD_MODEL", alias);
        var builder = RestClient.builder().requestInterceptor((http, body, ignored) -> {
            JsonNode json = JSON.readTree(body);
            sent.add(json);
            model.generateAnswer(request(json.get("messages").get(json.get("messages").size() - 1)
                    .get("content").asString()));
            String result = engine.equals("ollama")
                    ? "{\"model\":\"mock\",\"message\":{\"role\":\"assistant\",\"content\":\"모의 결과\"},\"done\":true}"
                    : "{\"choices\":[{\"finish_reason\":\"stop\",\"message\":{\"role\":\"assistant\",\"content\":\"모의 결과\"}}]}";
            var response = new MockClientHttpResponse(result.getBytes(StandardCharsets.UTF_8), HttpStatus.OK);
            response.getHeaders().setContentType(MediaType.APPLICATION_JSON);
            return response;
        });
        return new AiGuardModelService(chat, runtime, environment, builder, metrics,
                Duration.ofSeconds(1), Duration.ofSeconds(3), maxConcurrent, permitWait);
    }

    private static void assertBusy(AiGuardModelService guard) {
        assertThatThrownBy(() -> guard.generate(request("guard")))
                .isInstanceOfSatisfying(LlmException.class,
                        error -> assertThat(error.getErrorCode()).isEqualTo(LlmErrorCode.LLM_BUSY));
    }

    private static LlmRequestDto request(String text) {
        return new LlmRequestDto(List.of(new LlmMessageRequestDto(LlmMessageRole.USER, text)));
    }

    private static Thread start(Runnable call, AtomicReference<Throwable> failure) {
        return Thread.ofVirtual().start(() -> {
            try { call.run(); } catch (Throwable error) { failure.set(error); }
        });
    }

    private static void join(Thread thread) throws InterruptedException {
        thread.join(5000);
        assertThat(thread.isAlive()).isFalse();
    }

    private static class SimulatedModel implements LlmClient {
        private final AtomicInteger guardCalls = new AtomicInteger();
        private final AtomicInteger active = new AtomicInteger();
        private final AtomicInteger peak = new AtomicInteger();
        private final AtomicReference<RuntimeException> guardFailure = new AtomicReference<>();
        private volatile Gate answerGate;
        private volatile Gate guardGate;

        @Override
        public LlmResponseDto generateAnswer(LlmRequestDto request) {
            boolean answer = request.messages().getLast().content().equals("answer");
            if (!answer) guardCalls.incrementAndGet();
            peak.accumulateAndGet(active.incrementAndGet(), Math::max);
            try {
                Gate gate = answer ? answerGate : guardGate;
                if (gate != null) gate.awaitRelease();
                RuntimeException failure = answer ? null : guardFailure.getAndSet(null);
                if (failure != null) throw failure;
                return new LlmResponseDto("모의 결과");
            } finally { active.decrementAndGet(); }
        }

        @Override
        public String getModelName() { return "chat-alias"; }
    }

    private static class Gate {
        private final CountDownLatch started = new CountDownLatch(1);
        private final CountDownLatch interrupted = new CountDownLatch(1);
        private final CountDownLatch release = new CountDownLatch(1);

        private void awaitStarted() throws InterruptedException {
            assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();
        }

        private void awaitRelease() {
            started.countDown();
            // Worker의 취소 후에도 실제 전송이 끝나지 않는 상황을 재현합니다.
            boolean wasInterrupted = false;
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            try {
                while (release.getCount() > 0) {
                    try {
                        long remaining = deadline - System.nanoTime();
                        assertThat(remaining).isPositive();
                        assertThat(release.await(remaining, TimeUnit.NANOSECONDS)).isTrue();
                    } catch (InterruptedException error) {
                        wasInterrupted = true;
                        interrupted.countDown();
                    }
                }
            } finally {
                if (wasInterrupted) Thread.currentThread().interrupt();
            }
        }
    }
}
