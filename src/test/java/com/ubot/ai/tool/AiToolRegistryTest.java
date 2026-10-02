package com.ubot.ai.tool;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.ubot.location.service.LocationService;
import com.ubot.store.service.StoreService;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallback;

class AiToolRegistryTest {

    private final AiToolRegistry registry = new AiToolRegistry(
            new StoreTools(mock(StoreService.class), mock(LocationService.class)));

    @Test
    void resolvesStoreSearchToolWithoutExposingToolContextToModel() {
        var callbacks = registry.resolve(Set.of(AiTool.STORE_SEARCH));

        assertThat(callbacks).extracting(callback -> callback.getToolDefinition().name())
                .containsExactly("findNearbyStores");
        // 좌표는 서버가 ToolContext로 넣으므로 LLM에 보이는 스키마에는 place만 있어야 합니다.
        String schema = callbacks.getFirst().getToolDefinition().inputSchema();
        assertThat(schema).contains("place").doesNotContain("toolContext").doesNotContain("latitude");
    }

    @Test
    void resolvesNothingWithoutTools() {
        assertThat(registry.resolve(Set.<AiTool>of())).isEmpty();
    }

    @Test
    void registersEveryAiTool() {
        for (AiTool tool : AiTool.values()) {
            assertThat(registry.resolve(Set.of(tool))).as(tool.name()).isNotEmpty()
                    .allSatisfy(callback -> assertThat(callback).isInstanceOf(ToolCallback.class));
        }
    }
}
