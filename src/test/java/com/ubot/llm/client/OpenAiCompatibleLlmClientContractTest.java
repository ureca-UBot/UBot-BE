package com.ubot.llm.client;

import com.ubot.llm.config.LlmConfig;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.web.client.RestClient;

/** OpenAiCompatibleLlmClient가 공통 계약을 지키는지 /v1/chat/completions 응답 형식으로 확인합니다. */
class OpenAiCompatibleLlmClientContractTest extends LlmClientContractTest {

	@Override
	protected String chatPath() {
		return "/v1/chat/completions";
	}

	@Override
	protected LlmClient createClient(String serverUrl, String modelName, Duration readTimeout) {
		// 설정과 같은 형태로 기본 주소 끝에 /v1을 붙입니다.
		return new LlmConfig().openAiCompatibleLlmClient(RestClient.builder(), serverUrl + "/v1", modelName,
				Duration.ofSeconds(1), readTimeout);
	}

	@Override
	protected String answerResponse(String content) {
		return response(message(content), "stop");
	}

	@Override
	protected String truncatedResponse(String content) {
		return response(message(content), "length");
	}

	@Override
	protected String toolCallResponse(String toolName, Map<String, Object> arguments) {
		Map<String, Object> message = message(null);
		// OpenAI 호환 API는 도구 인자를 JSON 문자열로 돌려줍니다.
		message.put("tool_calls", List.of(Map.of(
				"id", "call-1",
				"type", "function",
				"function", Map.of("name", toolName, "arguments", toJson(arguments)))));
		return response(message, "tool_calls");
	}

	private Map<String, Object> message(String content) {
		Map<String, Object> message = new LinkedHashMap<>();
		message.put("role", "assistant");
		message.put("content", content);
		return message;
	}

	private String response(Map<String, Object> message, String finishReason) {
		return toJson(Map.of(
				"id", "chatcmpl-1",
				"object", "chat.completion",
				"model", MODEL_NAME,
				"choices", List.of(Map.of("index", 0, "message", message, "finish_reason", finishReason)),
				"usage", Map.of("prompt_tokens", 12, "completion_tokens", 8, "total_tokens", 20)));
	}
}
