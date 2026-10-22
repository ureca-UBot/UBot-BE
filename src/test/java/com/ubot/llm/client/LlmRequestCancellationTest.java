package com.ubot.llm.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import com.sun.net.httpserver.HttpServer;
import com.ubot.llm.config.LlmClientFactory;
import com.ubot.llm.dto.request.LlmMessageRequestDto;
import com.ubot.llm.dto.request.LlmRequestDto;
import com.ubot.llm.enums.LlmMessageRole;
import com.ubot.llm.service.LlmService;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.web.client.RestClient;

/** 네트워크는 임시 localhost 서버만 사용하며 실제 모델을 호출하지 않습니다. */
class LlmRequestCancellationTest {

	@ParameterizedTest
	@ValueSource(ints = {1, 2})
	void interruptCancelsOnlyThisHttpRequestAndReleasesItsPermit(int concurrency) throws Exception {
		var entered = new CountDownLatch(1);
		var releaseA = new CountDownLatch(1);
		var releaseB = new CountDownLatch(1);
		var stoppedA = new CountDownLatch(1);
		var failureA = new AtomicReference<Throwable>();
		var handlerThreads = Executors.newVirtualThreadPerTaskExecutor();
		var tasks = Executors.newVirtualThreadPerTaskExecutor();
		HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		server.setExecutor(handlerThreads);
		server.createContext("/v1/chat/completions", exchange -> {
			String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
			boolean cancelled = body.contains("cancel-this-question");
			try {
				if (cancelled) entered.countDown();
				(cancelled ? releaseA : releaseB).await(15, TimeUnit.SECONDS);
				byte[] response = "{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"other answer\"},\"finish_reason\":\"stop\"}]}"
						.getBytes(StandardCharsets.UTF_8);
				exchange.getResponseHeaders().set("Content-Type", "application/json");
				exchange.sendResponseHeaders(200, response.length);
				exchange.getResponseBody().write(response);
			} catch (InterruptedException error) {
				Thread.currentThread().interrupt();
			} finally {
				exchange.close();
			}
		});
		server.start();
		try {
			LlmClient client = new LlmClientFactory().createOpenAiCompatible(RestClient.builder(),
					"http://127.0.0.1:" + server.getAddress().getPort() + "/v1", "stub-only",
					Duration.ofSeconds(2), Duration.ofSeconds(20));
			LlmService service = new LlmService(client, concurrency);
			var a = tasks.submit(() -> {
				try { service.generateAnswer(question("cancel-this-question")); }
				catch (Throwable error) { failureA.set(error); }
				finally { stoppedA.countDown(); }
			});
			assertThat(entered.await(3, TimeUnit.SECONDS)).isTrue();
			var b = tasks.submit(() -> service.generateAnswer(question("keep-other-question")));
			a.cancel(true);
			assertThat(stoppedA.await(3, TimeUnit.SECONDS)).as("HTTP 호출 대기가 실제로 해제됨").isTrue();
			assertThat(failureA.get()).isInstanceOf(CancellationException.class);
			releaseB.countDown();
			assertThat(b.get(3, TimeUnit.SECONDS).answer()).isEqualTo("other answer");
			assertThat(b.isCancelled()).isFalse();
		} finally {
			releaseA.countDown();
			releaseB.countDown();
			server.stop(0);
			tasks.shutdownNow();
			handlerThreads.shutdownNow();
		}
	}

	@Test
	void alreadyInterruptedTaskDoesNotStartModelRequest() {
		LlmClient client = mock(LlmClient.class);
		try {
			Thread.currentThread().interrupt();
			org.assertj.core.api.Assertions.assertThatThrownBy(() ->
					new LlmService(client).generateAnswer(question("question")))
					.isInstanceOf(CancellationException.class);
			verifyNoInteractions(client);
		} finally { Thread.interrupted(); }
	}

	private static LlmRequestDto question(String text) {
		return new LlmRequestDto(List.of(new LlmMessageRequestDto(LlmMessageRole.USER, text)));
	}
}
