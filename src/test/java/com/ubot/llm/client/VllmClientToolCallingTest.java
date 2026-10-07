package com.ubot.llm.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.sun.net.httpserver.HttpServer;
import com.ubot.ai.tool.AiTool;
import com.ubot.ai.tool.AiToolRegistry;
import com.ubot.ai.tool.NearbyStoreSearcher;
import com.ubot.ai.tool.StoreSearchRecorder;
import com.ubot.ai.tool.StoreTools;
import com.ubot.llm.config.LlmConfig;
import com.ubot.llm.dto.request.LlmMessageRequestDto;
import com.ubot.llm.dto.request.LlmRequestDto;
import com.ubot.llm.enums.LlmMessageRole;
import com.ubot.llm.exception.LlmErrorCode;
import com.ubot.llm.exception.LlmException;
import com.ubot.location.dto.LocationSearchResponse;
import com.ubot.location.service.LocationService;
import com.ubot.store.dto.NearbyStoreResponseDto;
import com.ubot.store.service.StoreService;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.metadata.ToolMetadata;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** 도구가 있는 요청이 vLLM 경로에서도 도구를 실행하고, 그 결과를 대화에 붙여 LLM을 다시 호출하는지 확인합니다. */
class VllmClientToolCallingTest {

	private static final JsonMapper JSON = JsonMapper.builder().build();

	private final StoreService storeService = mock(StoreService.class);
	private final LocationService locationService = mock(LocationService.class);
	private final AiToolRegistry registry =
			new AiToolRegistry(new StoreTools(new NearbyStoreSearcher(storeService, 3.0, 5), locationService));
	private final StoreSearchRecorder recorder = new StoreSearchRecorder();
	private final LlmRequestDto request = new LlmRequestDto(List.of(
			new LlmMessageRequestDto(LlmMessageRole.SYSTEM, "시스템"),
			new LlmMessageRequestDto(LlmMessageRole.USER, "강남역 근처 매장 알려줘")))
			.withTools(registry.resolve(Set.of(AiTool.STORE_SEARCH)), Map.of(
					StoreTools.QUESTION, "강남역 근처 매장 알려줘",
					StoreTools.RECORDER, recorder));

	// 서버가 차례로 돌려줄 응답과, 서버가 받은 요청 본문입니다.
	private final Queue<StubResponse> responses = new ConcurrentLinkedQueue<>();
	private final List<JsonNode> requests = new CopyOnWriteArrayList<>();

	private HttpServer server;
	private LlmClient client;

	@BeforeEach
	void setUp() throws IOException {
		when(locationService.search("강남역")).thenReturn(List.of(
				new LocationSearchResponse("강남역", "서울 강남구", "서울 강남구 강남대로", 37.5, 127.0)));
		when(storeService.getNearbyStoreList(37.5, 127.0, 3.0, List.of(), 5)).thenReturn(List.of(
				new NearbyStoreResponseDto(12L, "강남점", "서울", "강남구", "서울 강남구 테헤란로 1",
						"02-123-4567", "10:00~21:00", 37.49, 127.02, 0.42)));

		server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		server.createContext("/v1/chat/completions", exchange -> {
			requests.add(JSON.readTree(exchange.getRequestBody().readAllBytes()));
			StubResponse response = responses.poll();
			if (response == null) {
				// 준비한 응답보다 많이 호출하면 테스트가 실패하게 합니다.
				response = new StubResponse(500, "{\"error\":\"unexpected call\"}");
			}
			byte[] bytes = response.body().getBytes(StandardCharsets.UTF_8);
			exchange.getResponseHeaders().set("Content-Type", "application/json");
			exchange.sendResponseHeaders(response.status(), bytes.length);
			exchange.getResponseBody().write(bytes);
			exchange.close();
		});
		server.start();
		String baseUrl = "http://127.0.0.1:" + server.getAddress().getPort() + "/v1";
		client = new LlmConfig().vllmClient(RestClient.builder(), baseUrl, "ubot-chat",
				Duration.ofSeconds(1), Duration.ofSeconds(3));
	}

	@AfterEach
	void stopServer() {
		server.stop(0);
	}

	@Test
	void executesToolAndCallsModelAgain() {
		responses.add(toolCall("findNearbyStores", "{\\\"place\\\":\\\"강남역\\\"}"));
		responses.add(text("강남점이 가까워요."));

		var answer = client.generateAnswer(request);

		assertThat(answer.answer()).isEqualTo("강남점이 가까워요.");
		// LLM이 넘긴 장소명을 질문과 대조한 뒤, 카카오 좌표로 매장을 조회합니다. toolContext의 질문이 도구까지 전달됩니다.
		verify(storeService).getNearbyStoreList(37.5, 127.0, 3.0, List.of(), 5);
		// 요청이 넣은 같은 recorder에 조회 결과가 남아 화면에 전달할 수 있습니다.
		assertThat(recorder.result()).hasValueSatisfying(result -> assertThat(result.stores())
				.extracting(NearbyStoreResponseDto::storeId).containsExactly(12L));

		assertThat(requests).hasSize(2);
		// 첫 요청에는 도구 정의가 OpenAI 호환 형식으로 실립니다.
		JsonNode tool = requests.getFirst().get("tools").get(0);
		assertThat(tool.get("type").asString()).isEqualTo("function");
		assertThat(tool.get("function").get("name").asString()).isEqualTo("findNearbyStores");
		assertThat(tool.get("function").get("description").asString()).contains("근처 매장");
		assertThat(tool.get("function").get("parameters").get("type").asString()).isEqualTo("object");
		assertThat(tool.get("function").get("parameters").get("properties").has("place")).isTrue();

		// 두 번째 요청에는 LLM의 도구 호출과 도구 실행 결과가 대화에 붙어 있어야 합니다.
		JsonNode messages = requests.get(1).get("messages");
		assertThat(messages.size()).isEqualTo(4);
		JsonNode assistant = messages.get(2);
		assertThat(assistant.get("role").asString()).isEqualTo("assistant");
		assertThat(assistant.get("tool_calls").get(0).get("id").asString()).isEqualTo("call-1");
		assertThat(assistant.get("tool_calls").get(0).get("function").get("arguments").asString())
				.isEqualTo("{\"place\":\"강남역\"}");
		JsonNode toolResult = messages.get(3);
		assertThat(toolResult.get("role").asString()).isEqualTo("tool");
		assertThat(toolResult.get("tool_call_id").asString()).isEqualTo("call-1");
		assertThat(toolResult.get("content").asString()).contains("[매장 ID: 12] 강남점");
		// 도구 정의는 다시 호출할 때도 함께 보냅니다.
		assertThat(requests.get(1).get("tools").size()).isEqualTo(1);
	}

	@Test
	void asksForLocationWhenModelCallsToolWithoutPlace() {
		// 장소 없이 물은 질문에는 LLM이 인자 없이 도구를 부릅니다. 빈 인자는 빈 JSON 객체로 맞춰 실행합니다.
		responses.add(toolCall("findNearbyStores", ""));
		responses.add(text("위치를 알려 주시면 가까운 매장을 찾아 드릴게요."));

		var answer = client.generateAnswer(request);

		assertThat(answer.answer()).isEqualTo("위치를 알려 주시면 가까운 매장을 찾아 드릴게요.");
		// Ollama 경로와 마찬가지로 화면이 위치를 요청하도록 표시가 남습니다.
		assertThat(recorder.isLocationRequired()).isTrue();
		JsonNode messages = requests.get(1).get("messages");
		assertThat(messages.get(2).get("tool_calls").get(0).get("function").get("arguments").asString())
				.isEqualTo("{}");
		assertThat(messages.get(3).get("content").asString()).contains("근처 매장을 조회할 수 없습니다");
	}

	@Test
	void stopsExecutingToolAfterCallLimitAndLetsModelFinish() {
		// LLM이 같은 도구를 계속 부르는 경우입니다. 도구당 3번까지만 실행하고,
		// 4번째 요청에는 실행 대신 한도 초과를 도구 결과로 돌려준 뒤 LLM이 답변을 마무리합니다.
		for (int call = 0; call < 4; call++) {
			responses.add(toolCall("findNearbyStores", "{\\\"place\\\":\\\"강남역\\\"}"));
		}
		responses.add(text("매장 정보를 정리했어요."));

		var answer = client.generateAnswer(request);

		assertThat(answer.answer()).isEqualTo("매장 정보를 정리했어요.");
		verify(locationService, times(3)).search("강남역");
		assertThat(requests).hasSize(5);
	}

	@Test
	void mapsModelFailureInToolPath() {
		responses.add(toolCall("findNearbyStores", "{\\\"place\\\":\\\"강남역\\\"}"));
		responses.add(new StubResponse(503, "{\"error\":{\"message\":\"unavailable\"}}"));

		assertThatThrownBy(() -> client.generateAnswer(request))
				.isInstanceOfSatisfying(LlmException.class, exception -> assertThat(exception.getErrorCode())
						.isEqualTo(LlmErrorCode.LLM_SERVICE_UNAVAILABLE));
		verify(locationService).search("강남역");
	}

	@Test
	void returnsToolResultDirectlyWhenToolAsksForIt() {
		ToolCallback directTool = new ToolCallback() {
			@Override
			public ToolDefinition getToolDefinition() {
				return ToolDefinition.builder()
						.name("lookupNotice")
						.description("공지 문구를 조회한다.")
						.inputSchema("{\"type\":\"object\",\"properties\":{}}")
						.build();
			}

			@Override
			public ToolMetadata getToolMetadata() {
				return ToolMetadata.builder().returnDirect(true).build();
			}

			@Override
			public String call(String toolInput) {
				return "도구가 만든 답변";
			}
		};
		responses.add(toolCall("lookupNotice", "{}"));

		var answer = client.generateAnswer(new LlmRequestDto(List.of(
				new LlmMessageRequestDto(LlmMessageRole.USER, "공지 알려줘")))
				.withTools(List.of(directTool), Map.of()));

		// 결과를 그대로 답변으로 쓰는 도구는 LLM을 다시 부르지 않습니다.
		assertThat(answer.answer()).isEqualTo("도구가 만든 답변");
		assertThat(requests).hasSize(1);
	}

	/** arguments는 JSON 문자열 안에 들어가므로 따옴표를 이스케이프한 형태로 받습니다. */
	private StubResponse toolCall(String name, String escapedArguments) {
		return new StubResponse(200, """
				{"id":"chatcmpl-1","object":"chat.completion","model":"ubot-chat",
				 "choices":[{"index":0,
				   "message":{"role":"assistant","content":null,
					 "tool_calls":[{"id":"call-1","type":"function",
									"function":{"name":"%s","arguments":"%s"}}]},
				   "finish_reason":"tool_calls"}]}
				""".formatted(name, escapedArguments));
	}

	private StubResponse text(String content) {
		return new StubResponse(200, """
				{"id":"chatcmpl-2","object":"chat.completion","model":"ubot-chat",
				 "choices":[{"index":0,
				   "message":{"role":"assistant","content":"%s","tool_calls":[]},
				   "finish_reason":"stop"}]}
				""".formatted(content));
	}

	private record StubResponse(int status, String body) {
	}
}
