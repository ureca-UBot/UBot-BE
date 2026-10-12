package com.ubot.llm.dto.request;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.ai.tool.ToolCallback;

/**
 * 프롬프트 담당이 구성한 메시지를 순서대로 전달합니다. 도구가 있으면 도구와 서버 측 도구 문맥도 함께 전달합니다.
 * responseSchema가 있으면 모델 출력을 이 JSON Schema로 강제합니다(도구가 있는 요청은 제외, 각 LlmClient 참고).
 */
public record LlmRequestDto(
        List<LlmMessageRequestDto> messages,
        List<ToolCallback> toolCallbacks,
        Map<String, Object> toolContext,
        Map<String, Object> responseSchema) {

    public LlmRequestDto {
        // 유효성은 LlmService에서 검사하고, 호출 중 원본 목록이 바뀌는 것은 방지합니다.
        if (messages != null) {
            messages = Collections.unmodifiableList(new ArrayList<>(messages));
        }
        toolCallbacks = toolCallbacks == null ? List.of() : List.copyOf(toolCallbacks);
        toolContext = toolContext == null ? Map.of() : Map.copyOf(toolContext);
        // 키 순서가 곧 출력 키 순서이므로 순서를 보존하는 복사본으로 둡니다.
        responseSchema = responseSchema == null ? null : Collections.unmodifiableMap(new LinkedHashMap<>(responseSchema));
    }

    /** 도구 없는 요청입니다. */
    public LlmRequestDto(List<LlmMessageRequestDto> messages) {
        this(messages, List.of(), Map.of(), null);
    }

    public LlmRequestDto(List<LlmMessageRequestDto> messages, List<ToolCallback> toolCallbacks,
            Map<String, Object> toolContext) {
        this(messages, toolCallbacks, toolContext, null);
    }

    public LlmRequestDto withTools(List<ToolCallback> callbacks, Map<String, Object> context) {
        return new LlmRequestDto(messages, callbacks, context, responseSchema);
    }

    public LlmRequestDto withResponseSchema(Map<String, Object> schema) {
        return new LlmRequestDto(messages, toolCallbacks, toolContext, schema);
    }

    public boolean hasTools() {
        return !toolCallbacks.isEmpty();
    }

    public boolean hasResponseSchema() {
        return responseSchema != null;
    }
}
