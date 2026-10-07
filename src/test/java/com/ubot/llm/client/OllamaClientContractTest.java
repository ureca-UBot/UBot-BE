package com.ubot.llm.client;

import com.ubot.llm.config.LlmConfig;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.web.client.RestClient;

/** OllamaClient가 공통 계약을 지키는지 Ollama의 /api/chat 응답 형식으로 확인합니다. */
class OllamaClientContractTest extends LlmClientContractTest {

	@Override
	protected String chatPath() {
		return "/api/chat";
	}

	@Override
	protected LlmClient createClient(String serverUrl, String modelName, Duration readTimeout) {
		return new LlmConfig().llmClient(RestClient.builder(), serverUrl, modelName,
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
		Map<String, Object> message = message("");
		// Ollama는 도구 인자를 문자열이 아니라 JSON 객체로 돌려줍니다.
		message.put("tool_calls", List.of(Map.of(
				"function", Map.of("name", toolName, "arguments", arguments))));
		return response(message, "stop");
	}

	private Map<String, Object> message(String content) {
		Map<String, Object> message = new LinkedHashMap<>();
		message.put("role", "assistant");
		message.put("content", content);
		return message;
	}

	private String response(Map<String, Object> message, String doneReason) {
		// Ollama가 마지막 응답에 항상 넣는 토큰 수까지 담습니다. 이 값이 있어야 Spring AI가 종료 사유를 읽습니다.
		return toJson(Map.of(
				"model", MODEL_NAME,
				"created_at", "2026-09-21T00:00:00Z",
				"message", message,
				"done", true,
				"done_reason", doneReason,
				"prompt_eval_count", 12,
				"eval_count", 8));
	}
}
