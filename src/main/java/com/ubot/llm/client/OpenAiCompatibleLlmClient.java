package com.ubot.llm.client;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.ubot.llm.dto.request.LlmMessageRequestDto;
import com.ubot.llm.dto.request.LlmRequestDto;
import com.ubot.llm.dto.response.LlmResponseDto;
import com.ubot.llm.exception.LlmErrorCode;
import com.ubot.llm.exception.LlmException;
import java.net.SocketTimeoutException;
import java.net.http.HttpTimeoutException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.DefaultToolCallingManager;
import org.springframework.ai.model.tool.ToolCallLimitBehavior;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.model.tool.ToolExecutionResult;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.http.MediaType;
import org.springframework.http.converter.HttpMessageConversionException;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * OpenAI 호환 chat completions API를 호출합니다. 운영 환경의 vLLM에 연결할 때 씁니다.
 * 도구 실행은 OllamaClient와 같은 Spring AI 도구 실행기에 맡겨, 두 구현체가 같은 방식으로 도구를 부릅니다.
 */
@Slf4j
public class OpenAiCompatibleLlmClient implements LlmClient {

	private static final String CHAT_COMPLETIONS_PATH = "/chat/completions";

	// OllamaClient와 같은 한도입니다. 한도를 넘으면 도구를 실행하지 않고 "한도 초과"를 도구 결과로 알려,
	// LLM이 그걸 보고 답변을 마무리하게 합니다.
	private static final int MAX_CALLS_PER_TOOL = 3;

	// 서버가 Thinking Mode를 켜 둔 경우 추론 텍스트가 답변 앞에 붙어 올 수 있어, 최종 답변만 남깁니다.
	private static final Pattern THINKING_BLOCK = Pattern.compile("(?s)^\\s*<think>.*?</think>\\s*");

	private static final int MAX_LOGGED_BODY_LENGTH = 300;
	private static final JsonMapper JSON_MAPPER = JsonMapper.builder().build();
	private static final TypeReference<Map<String, Object>> JSON_OBJECT = new TypeReference<>() {
	};

	private final RestClient restClient;
	private final String modelName;
	private final ToolCallingManager toolCallingManager;

	/** restClient의 기본 주소는 OpenAI 호환 API 주소입니다. 예: http://localhost:8000/v1 */
	public OpenAiCompatibleLlmClient(RestClient restClient, String modelName) {
		this.restClient = restClient;
		this.modelName = modelName;
		this.toolCallingManager = DefaultToolCallingManager.builder()
				.maxCallsPerTool(MAX_CALLS_PER_TOOL)
				.onLimitExceeded(ToolCallLimitBehavior.RETURN_ERROR_RESPONSE)
				.build();
	}

	@Override
	public LlmResponseDto generateAnswer(LlmRequestDto request) {
		if (!StringUtils.hasText(modelName)) {
			throw new LlmException(LlmErrorCode.LLM_MODEL_NOT_CONFIGURED);
		}

		// 우리 메시지 DTO를 Spring AI 메시지로 바꿔 두면 도구 실행기가 만든 대화 이력을 그대로 이어 쓸 수 있습니다.
		List<Message> messages = request.messages().stream().map(this::toMessage).toList();

		Completion completion;
		try {
			completion = request.hasTools()
					? completeWithTools(messages, request)
					: complete(messages, List.of());
		} catch (RuntimeException exception) {
			throw translateException(exception);
		}

		// 빈 결과·길이 제한으로 잘린 결과·도구 실행이 끝난 뒤에도 남은 도구 호출은 정상 답변으로 쓰지 않습니다.
		if (completion == null || completion.hasToolCalls() || completion.isTruncated()) {
			throw new LlmException(LlmErrorCode.LLM_RESPONSE_INVALID);
		}

		// 추론 과정은 제외하고 최종 답변 문자열만 꺼냅니다.
		String answer = removeThinking(completion.content());
		if (!StringUtils.hasText(answer)) {
			throw new LlmException(LlmErrorCode.LLM_RESPONSE_INVALID);
		}
		return new LlmResponseDto(answer);
	}

	/** LLM이 도구를 요청하는 동안, 도구 실행 결과를 대화에 붙여 다시 호출합니다. */
	private Completion completeWithTools(List<Message> messages, LlmRequestDto request) {
		ToolCallingChatOptions options = ToolCallingChatOptions.builder()
				.toolCallbacks(request.toolCallbacks())
				.toolContext(request.toolContext())
				.build();

		List<Message> conversation = messages;
		Completion completion = complete(conversation, request.toolCallbacks());
		while (completion != null && completion.hasToolCalls()) {
			ToolExecutionResult result = toolCallingManager.executeToolCalls(
					new Prompt(conversation, options), completion.toChatResponse());
			if (result.returnDirect()) {
				// 결과를 그대로 답변으로 쓰는 도구는 LLM을 다시 부르지 않습니다.
				return Completion.ofText(
						ToolExecutionResult.buildGenerations(result).getFirst().getOutput().getText());
			}
			conversation = result.conversationHistory();
			completion = complete(conversation, request.toolCallbacks());
		}
		return completion;
	}

	private Completion complete(List<Message> conversation, List<ToolCallback> tools) {
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("model", modelName);
		body.put("messages", conversation.stream()
				.flatMap(message -> toRequestMessages(message).stream())
				.toList());
		if (!tools.isEmpty()) {
			body.put("tools", tools.stream().map(this::toRequestTool).toList());
		}
		// 완성된 답변을 한 번에 받습니다. 현재 채팅 API는 SSE 스트리밍을 사용하지 않습니다.
		// temperature나 Thinking Mode 같은 생성 옵션은 보내지 않고 서버 기본 설정을 따릅니다.
		body.put("stream", false);

		ChatCompletion response = restClient.post()
				.uri(CHAT_COMPLETIONS_PATH)
				.contentType(MediaType.APPLICATION_JSON)
				.body(body)
				.retrieve()
				.body(ChatCompletion.class);
		return Completion.from(response);
	}

	private Message toMessage(LlmMessageRequestDto message) {
		return switch (message.role()) {
			case SYSTEM -> new SystemMessage(message.content());
			case USER -> new UserMessage(message.content());
			case ASSISTANT -> new AssistantMessage(message.content());
		};
	}

	private List<Map<String, Object>> toRequestMessages(Message message) {
		if (message instanceof AssistantMessage assistant) {
			Map<String, Object> requestMessage = createRequestMessage("assistant", assistant.getText());
			if (assistant.hasToolCalls()) {
				requestMessage.put("tool_calls",
						assistant.getToolCalls().stream().map(this::toRequestToolCall).toList());
			}
			return List.of(requestMessage);
		}
		if (message instanceof ToolResponseMessage toolResponse) {
			// 도구 호출 하나마다 tool 메시지 하나로 결과를 돌려줍니다.
			return toolResponse.getResponses().stream()
					.map(response -> {
						Map<String, Object> requestMessage = createRequestMessage("tool", response.responseData());
						requestMessage.put("tool_call_id", response.id());
						return requestMessage;
					})
					.toList();
		}
		return List.of(createRequestMessage(message.getMessageType().getValue(), message.getText()));
	}

	private Map<String, Object> createRequestMessage(String role, String content) {
		Map<String, Object> requestMessage = new LinkedHashMap<>();
		requestMessage.put("role", role);
		requestMessage.put("content", content);
		return requestMessage;
	}

	private Map<String, Object> toRequestToolCall(AssistantMessage.ToolCall toolCall) {
		return Map.of(
				"id", toolCall.id(),
				"type", "function",
				"function", Map.of("name", toolCall.name(), "arguments", toolCall.arguments()));
	}

	private Map<String, Object> toRequestTool(ToolCallback toolCallback) {
		ToolDefinition definition = toolCallback.getToolDefinition();
		// Spring AI가 만든 JSON Schema 문자열을 parameters 객체로 전달합니다.
		return Map.of(
				"type", "function",
				"function", Map.of(
						"name", definition.name(),
						"description", definition.description(),
						"parameters", JSON_MAPPER.readValue(definition.inputSchema(), JSON_OBJECT)));
	}

	private String removeThinking(String content) {
		return content == null ? null : THINKING_BLOCK.matcher(content).replaceFirst("");
	}

	private LlmException translateException(RuntimeException exception) {
		for (Throwable cause = exception; cause != null; cause = cause.getCause()) {
			if (cause instanceof HttpTimeoutException || cause instanceof SocketTimeoutException) {
				return new LlmException(LlmErrorCode.LLM_TIMEOUT, exception);
			}
			if (cause instanceof HttpMessageConversionException) {
				return new LlmException(LlmErrorCode.LLM_RESPONSE_INVALID, exception);
			}
		}
		if (exception instanceof RestClientResponseException response) {
			// 모델 이름 불일치나 도구 호출 미지원 같은 서버 설정 문제를 로그에서 구분할 수 있게 남깁니다.
			log.warn("LLM 서버가 오류 응답을 반환했습니다: 상태코드={}, 응답={}",
					response.getStatusCode().value(), abbreviate(response.getResponseBodyAsString()));
		}
		return new LlmException(LlmErrorCode.LLM_SERVICE_UNAVAILABLE, exception);
	}

	private String abbreviate(String body) {
		String singleLine = body.replaceAll("\\s+", " ");
		return singleLine.length() <= MAX_LOGGED_BODY_LENGTH
				? singleLine
				: singleLine.substring(0, MAX_LOGGED_BODY_LENGTH) + "...";
	}

	/** 응답 하나에서 이 클라이언트가 쓰는 값만 모읍니다. */
	private record Completion(String content, List<AssistantMessage.ToolCall> toolCalls, String finishReason) {

		static Completion from(ChatCompletion response) {
			if (response == null || response.choices() == null || response.choices().isEmpty()) {
				return null;
			}
			Choice choice = response.choices().getFirst();
			if (choice == null || choice.message() == null) {
				return null;
			}
			List<ResponseToolCall> requested = choice.message().toolCalls();
			List<AssistantMessage.ToolCall> toolCalls = requested == null
					? List.of()
					: requested.stream().map(Completion::toToolCall).toList();
			return new Completion(choice.message().content(), toolCalls, choice.finishReason());
		}

		static Completion ofText(String content) {
			return new Completion(content, List.of(), null);
		}

		private static AssistantMessage.ToolCall toToolCall(ResponseToolCall toolCall) {
			ResponseFunction function = toolCall.function();
			String name = function == null || function.name() == null ? "" : function.name();
			// 인자 없이 호출한 도구는 빈 JSON 객체로 맞춰, 실행할 때와 대화에 다시 실을 때 같은 값을 씁니다.
			String arguments = function == null || !StringUtils.hasText(function.arguments())
					? "{}"
					: function.arguments();
			return new AssistantMessage.ToolCall(toolCall.id() == null ? "" : toolCall.id(), "function", name, arguments);
		}

		boolean hasToolCalls() {
			return !toolCalls.isEmpty();
		}

		boolean isTruncated() {
			return "length".equals(finishReason);
		}

		ChatResponse toChatResponse() {
			AssistantMessage message = AssistantMessage.builder()
					.content(content == null ? "" : content)
					.toolCalls(toolCalls)
					.build();
			return new ChatResponse(List.of(new Generation(message)));
		}
	}

	// vLLM 응답 JSON에서 필요한 필드만 읽습니다.
	@JsonIgnoreProperties(ignoreUnknown = true)
	private record ChatCompletion(List<Choice> choices) {
	}

	@JsonIgnoreProperties(ignoreUnknown = true)
	private record Choice(ResponseMessage message, @JsonProperty("finish_reason") String finishReason) {
	}

	@JsonIgnoreProperties(ignoreUnknown = true)
	private record ResponseMessage(String content, @JsonProperty("tool_calls") List<ResponseToolCall> toolCalls) {
	}

	@JsonIgnoreProperties(ignoreUnknown = true)
	private record ResponseToolCall(String id, ResponseFunction function) {
	}

	@JsonIgnoreProperties(ignoreUnknown = true)
	private record ResponseFunction(String name, String arguments) {
	}
}
