package com.ubot.llm.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import com.ubot.llm.config.LlmConfig;
import com.ubot.llm.dto.request.LlmMessageRequestDto;
import com.ubot.llm.dto.request.LlmRequestDto;
import com.ubot.llm.enums.LlmMessageRole;
import com.ubot.llm.exception.LlmErrorCode;
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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.json.JsonMapper;

class VllmClientHttpTest {

	private static final String MODEL_NAME = "ubot-chat";

	private HttpServer server;

	@AfterEach
	void stopServer() {
		if (server != null) {
			server.stop(0);
		}
	}

	@Test
	void sendsConfiguredModelAndOrderedMessagesToChatCompletions() throws IOException {
		var capturedBody = new AtomicReference<String>();
		var service = createService(exchange -> {
			capturedBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
			respond(exchange, 200, completion("\"가까운 매장에서 재발급할 수 있습니다.\"", "stop"));
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
		assertThat(body.get("messages").get(1).get("role").asString()).isEqualTo("user");
		assertThat(body.get("messages").get(2).get("role").asString()).isEqualTo("assistant");
		assertThat(body.get("messages").get(3).get("content").asString())
				.isEqualTo(request.messages().getLast().content());
		// 도구가 없는 요청에는 tools를 보내지 않고, 추론 텍스트가 섞이지 않게 Thinking Mode를 끕니다.
		assertThat(body.has("tools")).isFalse();
		assertThat(body.get("chat_template_kwargs").get("enable_thinking").asBoolean()).isFalse();
		assertThat(result.answer()).isEqualTo("가까운 매장에서 재발급할 수 있습니다.");
	}

	@Test
	void doesNotReusePreviousRequestAsChatHistory() throws IOException {
		var capturedBody = new AtomicReference<String>();
		var service = createService(exchange -> {
			capturedBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
			respond(exchange, 200, completion("\"답변\"", "stop"));
		}, Duration.ofSeconds(3));

		service.generateAnswer(question("첫 번째 사용자 질문"));
		service.generateAnswer(question("다른 사용자 질문"));

		var body = JsonMapper.builder().build().readTree(capturedBody.get());
		assertThat(body.get("messages").size()).isEqualTo(1);
		assertThat(body.get("messages").get(0).get("content").asString()).isEqualTo("다른 사용자 질문");
	}

	@Test
	void returnsOnlyFinalAnswerWithoutThinking() throws IOException {
		var service = createService(exchange -> respond(exchange, 200,
				completion("\"<think>\\nFAQ 12를 근거로 답한다.\\n</think>\\n\\n가까운 매장에서 재발급할 수 있습니다.\"", "stop")),
				Duration.ofSeconds(3));

		assertThat(service.generateAnswer(question("유심 재발급 방법")).answer())
				.isEqualTo("가까운 매장에서 재발급할 수 있습니다.");
	}

	@Test
	void refusesMissingModelWithoutCallingServer() throws IOException {
		var calls = new AtomicInteger();
		var service = createService(exchange -> {
			calls.incrementAndGet();
			respond(exchange, 200, completion("\"답변\"", "stop"));
		}, Duration.ofSeconds(3), " ");

		assertFailure(service, LlmErrorCode.LLM_MODEL_NOT_CONFIGURED);
		assertThat(calls.get()).isZero();
	}

	@ParameterizedTest
	@ValueSource(ints = {400, 404, 503})
	void reportsErrorResponseWithoutRetrying(int status) throws IOException {
		var calls = new AtomicInteger();
		var service = createService(exchange -> {
			calls.incrementAndGet();
			respond(exchange, status, "{\"error\":{\"message\":\"unavailable\",\"code\":" + status + "}}");
		}, Duration.ofSeconds(3));

		assertFailure(service, LlmErrorCode.LLM_SERVICE_UNAVAILABLE);
		assertThat(calls.get()).isEqualTo(1);
	}

	@Test
	void mapsRefusedConnectionToUnavailable() {
		var client = new LlmConfig().vllmClient(RestClient.builder(), "http://127.0.0.1:1/v1", MODEL_NAME,
				Duration.ofSeconds(1), Duration.ofSeconds(3));

		assertFailure(new LlmService(client), LlmErrorCode.LLM_SERVICE_UNAVAILABLE);
	}

	@Test
	void reportsMalformedJson() throws IOException {
		var service = createService(exchange -> respond(exchange, 200, "not-json"), Duration.ofSeconds(3));

		assertFailure(service, LlmErrorCode.LLM_RESPONSE_INVALID);
	}

	@ParameterizedTest
	@ValueSource(strings = {
			"{}",
			"{\"choices\":[]}",
			"{\"choices\":[{\"index\":0,\"finish_reason\":\"stop\"}]}",
			"{\"choices\":[{\"index\":0,\"message\":{\"role\":\"assistant\",\"content\":null},\"finish_reason\":\"stop\"}]}",
			"{\"choices\":[{\"index\":0,\"message\":{\"role\":\"assistant\",\"content\":\"\"},\"finish_reason\":\"stop\"}]}",
			"{\"choices\":[{\"index\":0,\"message\":{\"role\":\"assistant\",\"content\":\" \\n \"},\"finish_reason\":\"stop\"}]}",
			"{\"choices\":[{\"index\":0,\"message\":{\"role\":\"assistant\",\"content\":\"<think>추론만 있음</think>\"},\"finish_reason\":\"stop\"}]}"
	})
	void rejectsMissingOrEmptyAnswer(String responseBody) throws IOException {
		var service = createService(exchange -> respond(exchange, 200, responseBody), Duration.ofSeconds(3));

		assertFailure(service, LlmErrorCode.LLM_RESPONSE_INVALID);
	}

	@Test
	void rejectsTruncatedAnswer() throws IOException {
		var service = createService(exchange -> respond(exchange, 200, completion("\"미완성 답변\"", "length")),
				Duration.ofSeconds(3));

		assertFailure(service, LlmErrorCode.LLM_RESPONSE_INVALID);
	}

	@Test
	void rejectsToolCallsWhenNoToolWasOffered() throws IOException {
		var service = createService(exchange -> respond(exchange, 200, """
				{"choices":[{"index":0,"message":{"role":"assistant","content":"도구 호출 요청",
				  "tool_calls":[{"id":"call-1","type":"function",
								 "function":{"name":"findNearbyStores","arguments":"{}"}}]},
				  "finish_reason":"tool_calls"}]}
				"""), Duration.ofSeconds(3));

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
		return createService(handler, readTimeout, MODEL_NAME);
	}

	private LlmService createService(HttpHandler handler, Duration readTimeout, String modelName)
			throws IOException {
		// 외부 vLLM 서버 없이 실제 HTTP 요청을 검증합니다. 설정과 같은 형태로 기본 주소 끝에 /v1을 붙입니다.
		server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		server.createContext("/v1/chat/completions", handler);
		server.start();
		String baseUrl = "http://127.0.0.1:" + server.getAddress().getPort() + "/v1";
		var client = new LlmConfig().vllmClient(RestClient.builder(), baseUrl, modelName,
				Duration.ofSeconds(1), readTimeout);
		return new LlmService(client);
	}

	private LlmRequestDto question(String content) {
		return new LlmRequestDto(List.of(new LlmMessageRequestDto(LlmMessageRole.USER, content)));
	}

	/** vLLM이 실제로 돌려주는 형태처럼, 이 클라이언트가 읽지 않는 필드도 함께 담습니다. */
	private String completion(String contentJson, String finishReason) {
		return """
				{"id":"chatcmpl-1","object":"chat.completion","created":1790000000,"model":"ubot-chat",
				 "choices":[{"index":0,
				   "message":{"role":"assistant","content":%s,"reasoning_content":null,"tool_calls":[]},
				   "logprobs":null,"finish_reason":"%s","stop_reason":null}],
				 "usage":{"prompt_tokens":12,"completion_tokens":8,"total_tokens":20}}
				""".formatted(contentJson, finishReason);
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
