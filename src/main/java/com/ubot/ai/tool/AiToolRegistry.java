package com.ubot.ai.tool;

import java.util.Arrays;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.ai.support.ToolCallbacks;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.stereotype.Component;

/** AiTool 값을 Spring AI ToolCallback으로 바꿉니다. */
@Component
public class AiToolRegistry {

    private final Map<AiTool, List<ToolCallback>> callbacks = new EnumMap<>(AiTool.class);

    public AiToolRegistry(StoreTools storeTools) {
        // @Tool 메서드를 ToolCallback으로 변환합니다. 리플렉션이라 기동 시 한 번만 합니다.
        callbacks.put(AiTool.STORE_SEARCH, List.of(ToolCallbacks.from(storeTools)));

        // 새 AiTool을 추가하고 등록을 빠뜨리면 요청 중이 아니라 기동 시 실패하게 합니다.
        List<AiTool> missing = Arrays.stream(AiTool.values()).filter(tool -> !callbacks.containsKey(tool)).toList();
        if (!missing.isEmpty()) {
            throw new IllegalStateException("등록되지 않은 AI 도구가 있습니다: " + missing);
        }
    }

    public List<ToolCallback> resolve(Set<AiTool> tools) {
        return tools.stream().flatMap(tool -> callbacks.get(tool).stream()).toList();
    }
}
