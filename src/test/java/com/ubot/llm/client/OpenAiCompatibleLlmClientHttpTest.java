package com.ubot.llm.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.metadata.ToolMetadata;
import org.springframework.web.client.RestClient;

import com.ubot.ai.tool.AiTool;
import com.ubot.ai.tool.AiToolRegistry;
import com.ubot.ai.tool.NearbyStoreSearcher;
import com.ubot.ai.tool.StoreSearchRecorder;
import com.ubot.ai.tool.StoreTools;
import com.ubot.llm.config.LlmClientFactory;
import com.ubot.llm.dto.request.LlmMessageRequestDto;
import com.ubot.llm.dto.request.LlmRequestDto;
import com.ubot.llm.enums.LlmMessageRole;
import com.ubot.llm.exception.LlmErrorCode;
import com.ubot.llm.exception.LlmException;
import com.ubot.location.service.LocationService;
import com.ubot.store.service.StoreService;

import tools.jackson.databind.JsonNode;

/** 공통 계약에 없는, OpenAI 호환 API의 요청·응답 형식 변환을 확인합니다. */
class OpenAiCompatibleLlmClientHttpTest {

	private static final String MODEL_NAME = "ubot-chat";

	private StubLlmServer server;
	private LlmClient client;

	@BeforeEach
	void startServer() throws IOException {
		server = new StubLlmServer("/v1/chat/completions");
		client = new LlmClientFactory().createOpenAiCompatible(
		        RestClient.builder(),
		        server.url() + "/v1",
		        MODEL_NAME,
		        Duration.ofSeconds(1),
		        Duration.ofSeconds(3)
		);
	}

	@AfterEach
	void stopServer() {
		server.close();
	}

	@Test
	void sendsModelAndOrderedMessagesToChatCompletions() {
		server.respond(completion("\"가까운 매장에서 재발급할 수 있습니다.\"", "stop"));
		var request = new LlmRequestDto(List.of(
				new LlmMessageRequestDto(LlmMessageRole.SYSTEM, "제공된 FAQ만 참고하세요."),
				new LlmMessageRequestDto(LlmMessageRole.USER, "이전 질문"),
				new LlmMessageRequestDto(LlmMessageRole.ASSISTANT, "이전 답변"),
				new LlmMessageRequestDto(LlmMessageRole.USER,
						"질문: 유심 재발급 방법\nFAQ 12: 유심 재발급은 가까운 매장에서 가능합니다.")));

		var result = client.generateAnswer(request);

		JsonNode body = server.requests().getFirst();
		assertThat(body.get("model").asString()).isEqualTo(MODEL_NAME);
		assertThat(body.get("stream").asBoolean()).isFalse();
		assertThat(body.get("messages").size()).isEqualTo(4);
		assertThat(body.get("messages").get(0).get("role").asString()).isEqualTo("system");
		assertThat(body.get("messages").get(1).get("role").asString()).isEqualTo("user");
		assertThat(body.get("messages").get(2).get("role").asString()).isEqualTo("assistant");
		assertThat(body.get("messages").get(3).get("content").asString())
				.isEqualTo(request.messages().getLast().content());
		// 도구가 없는 요청에는 tools를 보내지 않고, 생성 옵션은 서버 기본 설정에 맡깁니다.
		assertThat(body.has("tools")).isFalse();
		assertThat(body.has("temperature")).isFalse();
		assertThat(body.has("chat_template_kwargs")).isFalse();
		assertThat(result.answer()).isEqualTo("가까운 매장에서 재발급할 수 있습니다.");
	}

	@Test
	void doesNotReusePreviousRequestAsChatHistory() {
		server.respond(completion("\"첫 답변\"", "stop"));
		server.respond(completion("\"다음 답변\"", "stop"));

		client.generateAnswer(question("첫 번째 사용자 질문"));
		client.generateAnswer(question("다른 사용자 질문"));

		JsonNode body = server.requests().get(1);
		assertThat(body.get("messages").size()).isEqualTo(1);
		assertThat(body.get("messages").get(0).get("content").asString()).isEqualTo("다른 사용자 질문");
	}

	@Test
	void sendsToolDefinitionsAndToolResultsInOpenAiFormat() {
		var recorder = new StoreSearchRecorder();
		// 장소 없이 물은 질문에는 LLM이 인자 없이 도구를 부릅니다. 빈 인자는 빈 JSON 객체로 맞춰 실행합니다.
		server.respond(toolCall("findNearbyStores", ""));
		server.respond(completion("\"위치를 알려 주시면 가까운 매장을 찾아 드릴게요.\"", "stop"));

		var answer = client.generateAnswer(storeQuestion(recorder));

		assertThat(answer.answer()).isEqualTo("위치를 알려 주시면 가까운 매장을 찾아 드릴게요.");
		// Ollama 경로와 마찬가지로 화면이 위치를 요청하도록 표시가 남습니다.
		assertThat(recorder.isLocationRequired()).isTrue();

		// 첫 요청에는 도구 정의가 OpenAI 호환 형식으로 실립니다.
		JsonNode tool = server.requests().getFirst().get("tools").get(0);
		assertThat(tool.get("type").asString()).isEqualTo("function");
		assertThat(tool.get("function").get("name").asString()).isEqualTo("findNearbyStores");
		assertThat(tool.get("function").get("description").asString()).contains("근처 매장");
		assertThat(tool.get("function").get("parameters").get("type").asString()).isEqualTo("object");
		assertThat(tool.get("function").get("parameters").get("properties").has("place")).isTrue();

		// 두 번째 요청에는 LLM의 도구 호출과 도구 실행 결과가 대화에 붙어 있고, 도구 정의도 다시 보냅니다.
		JsonNode second = server.requests().get(1);
		JsonNode messages = second.get("messages");
		assertThat(messages.size()).isEqualTo(4);
		JsonNode assistant = messages.get(2);
		assertThat(assistant.get("role").asString()).isEqualTo("assistant");
		assertThat(assistant.get("tool_calls").get(0).get("id").asString()).isEqualTo("call-1");
		assertThat(assistant.get("tool_calls").get(0).get("function").get("arguments").asString()).isEqualTo("{}");
		JsonNode toolResult = messages.get(3);
		assertThat(toolResult.get("role").asString()).isEqualTo("tool");
		assertThat(toolResult.get("tool_call_id").asString()).isEqualTo("call-1");
		assertThat(toolResult.get("content").asString()).contains("근처 매장을 조회할 수 없습니다");
		assertThat(second.get("tools").size()).isEqualTo(1);
	}

	@Test
	void returnsOnlyFinalAnswerWithoutThinking() {
		server.respond(completion(
				"\"<think>\\nFAQ 12를 근거로 답한다.\\n</think>\\n\\n가까운 매장에서 재발급할 수 있습니다.\"", "stop"));

		assertThat(client.generateAnswer(question("유심 재발급 방법")).answer())
				.isEqualTo("가까운 매장에서 재발급할 수 있습니다.");
	}

	@ParameterizedTest
	@ValueSource(strings = {
			"{}",
			"{\"choices\":[]}",
			"{\"choices\":[{\"index\":0,\"finish_reason\":\"stop\"}]}",
			"{\"choices\":[{\"index\":0,\"message\":{\"role\":\"assistant\",\"content\":null},\"finish_reason\":\"stop\"}]}",
			"{\"choices\":[{\"index\":0,\"message\":{\"role\":\"assistant\",\"content\":\"<think>추론만 있음</think>\"},\"finish_reason\":\"stop\"}]}"
	})
	void rejectsResponseWithoutUsableAnswer(String responseBody) {
		server.respond(responseBody);

		assertFailure(question("질문"), LlmErrorCode.LLM_RESPONSE_INVALID);
	}

	@Test
	void rejectsToolCallsWhenNoToolWasOffered() {
		server.respond(toolCall("findNearbyStores", "{}"));

		assertFailure(question("질문"), LlmErrorCode.LLM_RESPONSE_INVALID);
	}

	@ParameterizedTest
	@ValueSource(ints = {400, 404})
	void reportsClientErrorAsUnavailable(int status) {
		// 모델 이름 불일치(404)나 도구 호출을 켜지 않은 서버(400)처럼 서버 설정이 맞지 않는 경우입니다.
		server.respond(status, "{\"error\":{\"message\":\"bad request\",\"code\":" + status + "}}");

		assertFailure(question("질문"), LlmErrorCode.LLM_SERVICE_UNAVAILABLE);
		assertThat(server.requests()).hasSize(1);
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
		server.respond(toolCall("lookupNotice", "{}"));

		var answer = client.generateAnswer(question("공지 알려줘").withTools(List.of(directTool), Map.of()));

		// 결과를 그대로 답변으로 쓰는 도구는 LLM을 다시 부르지 않습니다.
		assertThat(answer.answer()).isEqualTo("도구가 만든 답변");
		assertThat(server.requests()).hasSize(1);
	}

	private LlmRequestDto question(String content) {
		return new LlmRequestDto(List.of(new LlmMessageRequestDto(LlmMessageRole.USER, content)));
	}

	private LlmRequestDto storeQuestion(StoreSearchRecorder recorder) {
		var registry = new AiToolRegistry(new StoreTools(
				new NearbyStoreSearcher(mock(StoreService.class), 3.0, 5), mock(LocationService.class)));
		return new LlmRequestDto(List.of(
				new LlmMessageRequestDto(LlmMessageRole.SYSTEM, "시스템"),
				new LlmMessageRequestDto(LlmMessageRole.USER, "근처 매장 알려줘")))
				.withTools(registry.resolve(Set.of(AiTool.STORE_SEARCH)), Map.of(
						StoreTools.QUESTION, "근처 매장 알려줘",
						StoreTools.RECORDER, recorder));
	}

	private void assertFailure(LlmRequestDto request, LlmErrorCode expected) {
		assertThatThrownBy(() -> client.generateAnswer(request))
				.isInstanceOfSatisfying(LlmException.class,
						exception -> assertThat(exception.getErrorCode()).isEqualTo(expected));
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

	/** arguments는 JSON 문자열 안에 들어가므로 따옴표를 이스케이프한 형태로 받습니다. */
	private String toolCall(String name, String escapedArguments) {
		return """
				{"id":"chatcmpl-1","object":"chat.completion","model":"ubot-chat",
				 "choices":[{"index":0,
				   "message":{"role":"assistant","content":null,
				     "tool_calls":[{"id":"call-1","type":"function",
				                    "function":{"name":"%s","arguments":"%s"}}]},
				   "finish_reason":"tool_calls"}]}
				""".formatted(name, escapedArguments);
	}
}
