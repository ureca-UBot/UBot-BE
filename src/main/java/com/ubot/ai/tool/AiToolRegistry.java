package com.ubot.ai.tool;

import com.ubot.ai.tool.product.AddonTools;
import com.ubot.ai.tool.product.BundleTools;
import com.ubot.ai.tool.product.PlanTools;
import com.ubot.ai.tool.product.RoamingTools;
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

    public AiToolRegistry(StoreTools storeTools, UserDataTools userDataTools, PlanTools planTools, AddonTools addonTools,
            BundleTools bundleTools, RoamingTools roamingTools) {
        // @Tool 메서드를 ToolCallback으로 변환합니다. 리플렉션이라 기동 시 한 번만 합니다.
        callbacks.put(AiTool.STORE_SEARCH, List.of(ToolCallbacks.from(storeTools)));
        callbacks.put(AiTool.USER_DATA_LOOKUP, List.of(ToolCallbacks.from(userDataTools)));
        // 상품은 종류마다 조건이 달라 도구를 따로 두고, 핸들러가 질문에 필요한 종류의 도구만 붙입니다.
        callbacks.put(AiTool.PLAN_SEARCH, List.of(ToolCallbacks.from(planTools)));
        callbacks.put(AiTool.ADDON_SEARCH, List.of(ToolCallbacks.from(addonTools)));
        callbacks.put(AiTool.BUNDLE_SEARCH, List.of(ToolCallbacks.from(bundleTools)));
        callbacks.put(AiTool.ROAMING_SEARCH, List.of(ToolCallbacks.from(roamingTools)));

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
