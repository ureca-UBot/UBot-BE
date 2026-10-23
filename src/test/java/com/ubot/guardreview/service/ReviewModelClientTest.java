package com.ubot.guardreview.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockConstruction;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;

import com.ubot.guardreview.model.ReviewModels.Kind;
import com.ubot.guardreview.model.ReviewModels.Source;
import com.ubot.guardreview.model.ReviewModels.Stage;
import com.ubot.llm.client.OpenAiCompatibleLlmClient;
import com.ubot.llm.config.LlmClientFactory;
import com.ubot.llm.dto.request.LlmMessageRequestDto;
import com.ubot.llm.dto.request.LlmRequestDto;
import com.ubot.llm.enums.LlmMessageRole;
import com.ubot.llm.exception.LlmErrorCode;
import com.ubot.llm.exception.LlmException;
import com.ubot.llm.service.LlmService;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedConstruction;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.boot.convert.ApplicationConversionService;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.mock.http.client.MockClientHttpResponse;
import org.springframework.mock.http.client.MockClientHttpRequest;
import org.springframework.web.client.RestClient;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/** 마지막 HTTP 인터셉터에서 가짜 응답을 반환합니다. 소켓·실제 모델·DB를 사용하지 않습니다. */
class ReviewModelClientTest {

	private static final JsonMapper JSON = JsonMapper.builder().build();
	private static final TypeReference<Map<String, Object>> OBJECT = new TypeReference<>() {
	};
	private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(3);
	private static final Duration READ_TIMEOUT = Duration.ofSeconds(180);
	private final List<Map<String, Object>> requests = new ArrayList<>();
	private final List<URI> uris = new ArrayList<>();
	private MockedConstruction<LlmClientFactory> factories;

	@BeforeEach
	void HTTP_전송_준비만_가짜로_바꾸고_실제_공통_클라이언트로_직렬화와_응답처리를_검사한다() {
		// Windows JDK HTTP 클라이언트 초기화도 내부 루프백 소켓이 필요하므로 전송 준비만 대체합니다.
		factories = mockConstruction(LlmClientFactory.class,
				(factory,
						context) -> when(factory.createOpenAiCompatible(any(), anyString(), anyString(), any(), any()))
								.thenAnswer(call -> {
									RestClient.Builder configured = call.getArgument(0);
									String url = call.getArgument(1);
									String model = call.getArgument(2);
									return new OpenAiCompatibleLlmClient(configured.baseUrl(url.replaceAll("/+$", ""))
											.requestFactory((uri, method) -> new MockClientHttpRequest(method, uri))
											.build(), model);
								}));
	}

	@AfterEach
	void 가짜_전송_준비를_정리한다() {
		factories.close();
	}

	@Test
	void 전용주소가_없어도_컨텍스트가_기동되고_기존_LLM_빈_주입과_충돌하지_않는다() {
		new ApplicationContextRunner()
				.withInitializer(
						context -> context.getBeanFactory().setConversionService(new ApplicationConversionService()))
				.withBean(RestClient.Builder.class, RestClient::builder)
				.withBean(LlmService.class, () -> mock(LlmService.class))
				.withUserConfiguration(ReviewModelClient.class, LlmReviewAnalyzer.class).run(context -> {
					assertThat(context).hasNotFailed().hasSingleBean(LlmService.class)
							.hasSingleBean(ReviewModelClient.class).hasSingleBean(ReviewAnalyzer.class);
					assertThatThrownBy(() -> context.getBean(ReviewModelClient.class).generateJudgment(request()))
							.isInstanceOf(IllegalStateException.class).hasMessageContaining("연결 주소");
				});
	}

	@Test
	void 관리자_분석만_14B와_추론모드로_보내고_원본_빌더는_변경하지_않는다() {
		var builder = transport("{\"suggestions\":[]}", "stop");
		var client = client(builder, "http://review-model.invalid:8000/v1/");
		Map<String, Object> schema = Map.of("type", "object");
		assertThat(client.generateJudgment(request().withResponseSchema(schema)).answer())
				.isEqualTo("{\"suggestions\":[]}");
		assertThat(uris).containsExactly(URI.create("http://review-model.invalid:8000/v1/chat/completions"));
		assertThat(requests).hasSize(1);
		Map<String, Object> payload = requests.getFirst();
		assertThat(payload).containsEntry("model", "Qwen/Qwen3-14B-AWQ")
				.containsEntry("chat_template_kwargs", Map.of("enable_thinking", true))
				.containsEntry("max_tokens", 16384).containsEntry("top_k", 20).containsEntry("temperature", 0.6)
				.containsEntry("top_p", 0.95).containsEntry("min_p", 0).containsEntry("stream", false)
				.doesNotContainKeys("tools", "tool_choice");
		assertThat(JSON.writeValueAsString(payload.get("response_format"))).contains("json_schema", "strict");

		// 이 빌더를 공통 기능이 재사용해도 관리자 모델과 추론 옵션이 섞이지 않습니다.
		builder.baseUrl("http://shared-model.invalid/v1").build().post().uri("/chat/completions")
				.contentType(MediaType.APPLICATION_JSON).body(Map.of("model", "ubot-chat")).retrieve()
				.toBodilessEntity();
		assertThat(requests.get(1)).containsOnlyKeys("model").containsEntry("model", "ubot-chat");
	}

	@Test
	void 빈_전용주소는_기동시_통신하지_않고_분석시에_명확히_실패하며_공통모델로_대체하지_않는다() {
		var client = client(transport("{\"suggestions\":[]}", "stop"), " ");
		assertThat(requests).isEmpty();
		assertThatThrownBy(() -> client.generateJudgment(request())).isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("14B-AWQ 연결 주소");
		assertThat(requests).isEmpty();
	}

	@Test
	void HTTP가_아닌_주소나_인증정보가_포함된_주소를_모델에_보내지_않는다() {
		var builder = transport("{\"suggestions\":[]}", "stop");
		for (String url : List.of("file:///tmp/model", "http://user:secret@example.invalid/v1")) {
			assertThatThrownBy(() -> client(builder, url).generateJudgment(request()))
					.isInstanceOf(IllegalStateException.class);
		}
		assertThat(requests).isEmpty();
	}

	@Test
	void 모델이_없다는_응답은_다른_모델로_재시도하지_않는다() {
		AtomicInteger calls = new AtomicInteger();
		var builder = RestClient.builder().requestInterceptor((request, body, execution) -> {
			calls.incrementAndGet();
			return response("{\"error\":\"requested model not found\"}", HttpStatus.NOT_FOUND);
		});
		assertThatThrownBy(() -> client(builder, "http://review-model.invalid/v1").generateJudgment(request()))
				.isInstanceOf(LlmException.class);
		assertThat(calls).hasValue(1);
	}

	@Test
	void 시간초과는_기존_시간초과_오류로_전달하고_재시도하지_않는다() {
		AtomicInteger calls = new AtomicInteger();
		var builder = RestClient.builder().requestInterceptor((request, body, execution) -> {
			calls.incrementAndGet();
			throw new SocketTimeoutException("mock timeout");
		});
		assertThatThrownBy(() -> client(builder, "http://review-model.invalid/v1").generateJudgment(request()))
				.isInstanceOfSatisfying(LlmException.class,
						e -> assertThat(e.getErrorCode()).isEqualTo(LlmErrorCode.LLM_TIMEOUT));
		assertThat(calls).hasValue(1);
	}

	@Test
	void 잘린_추론응답을_정상_분석결과로_쓰지_않는다() {
		var client = client(transport("<think>아직 판단 중", "length"), "http://review-model.invalid/v1");
		assertThatThrownBy(() -> client.generateJudgment(request())).isInstanceOfSatisfying(LlmException.class,
				e -> assertThat(e.getErrorCode()).isEqualTo(LlmErrorCode.LLM_RESPONSE_INVALID));
	}

	@Test
	void 기존_추론제거와_후보변환을_재사용해_위험문장과_사유를_관리자_분석기로_전달한다() {
		String result = """
				{"suggestions":[{"kind":"WARNING","category":"SELF_HARM",
				"expression":"위험한 원문 구절","reason":"자해 실행 요청으로 의심됩니다.",
				"stage":"INPUT","intent":"HARMFUL_REQUEST"}]}
				""";
		var analyzer = new LlmReviewAnalyzer(new LlmService(mock(com.ubot.llm.client.LlmClient.class)),
				client(transport("<think>내부 추론</think>\n" + result, "stop"), "http://review-model.invalid/v1"));
		var suggestions = analyzer.analyze(new Source(1, "record", "위험한 원문 구절", "정상적인 거절"));
		assertThat(suggestions).hasSize(1);
		assertThat(suggestions.getFirst().kind()).isEqualTo(Kind.WARNING);
		assertThat(suggestions.getFirst().stage()).isEqualTo(Stage.INPUT);
		assertThat(suggestions.getFirst().reason()).isEqualTo("자해 실행 요청으로 의심됩니다.");
		assertThat(suggestions.getFirst().expression()).isEqualTo("위험한 원문 구절");
		assertThat(requests.getFirst()).containsEntry("model", ReviewModelClient.MODEL);
	}

	@Test
	void 도구_실행_요청은_관리자_모델에_보내지_않는다() {
		var client = client(transport("{}", "stop"), "http://review-model.invalid/v1");
		var withTools = request().withTools(List.of(mock(ToolCallback.class)), Map.of("userId", 1));
		assertThatThrownBy(() -> client.generateJudgment(withTools)).isInstanceOf(IllegalArgumentException.class);
		assertThat(requests).isEmpty();
	}

	private ReviewModelClient client(RestClient.Builder builder, String url) {
		return new ReviewModelClient(builder, url, CONNECT_TIMEOUT, READ_TIMEOUT);
	}

	private LlmRequestDto request() {
		return new LlmRequestDto(List.of(new LlmMessageRequestDto(LlmMessageRole.USER, "검사 자료")));
	}

	private RestClient.Builder transport(String answer, String finishReason) {
		return RestClient.builder().requestFactory((uri, method) -> new MockClientHttpRequest(method, uri))
				.requestInterceptor((request, body, execution) -> {
					requests.add(JSON.readValue(body, OBJECT));
					uris.add(request.getURI());
					String completed = JSON.writeValueAsString(Map.of("choices", List.of(Map.of("message",
							Map.of("role", "assistant", "content", answer), "finish_reason", finishReason))));
					return response(completed, HttpStatus.OK);
				});
	}

	private MockClientHttpResponse response(String body, HttpStatus status) {
		var response = new MockClientHttpResponse(body.getBytes(java.nio.charset.StandardCharsets.UTF_8), status);
		response.getHeaders().setContentType(MediaType.APPLICATION_JSON);
		return response;
	}
}
