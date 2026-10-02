package com.ubot.llm.dto.request;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import org.springframework.ai.tool.ToolCallback;

/** 프롬프트 담당이 구성한 메시지를 순서대로 전달합니다. 도구가 있으면 도구와 서버 측 도구 문맥도 함께 전달합니다. */
public record LlmRequestDto(
        List<LlmMessageRequestDto> messages,
        List<ToolCallback> toolCallbacks,
        Map<String, Object> toolContext) {

    public LlmRequestDto {
        // 유효성은 LlmService에서 검사하고, 호출 중 원본 목록이 바뀌는 것은 방지합니다.
        if (messages != null) {
            messages = Collections.unmodifiableList(new ArrayList<>(messages));
        }
        toolCallbacks = toolCallbacks == null ? List.of() : List.copyOf(toolCallbacks);
        toolContext = toolContext == null ? Map.of() : Map.copyOf(toolContext);
    }

    /** 도구 없는 요청입니다. */
    public LlmRequestDto(List<LlmMessageRequestDto> messages) {
        this(messages, List.of(), Map.of());
    }

    public LlmRequestDto withTools(List<ToolCallback> callbacks, Map<String, Object> context) {
        return new LlmRequestDto(messages, callbacks, context);
    }

    public boolean hasTools() {
        return !toolCallbacks.isEmpty();
    }
}
