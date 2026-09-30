package com.ubot.llm.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.ubot.ai.tool.AiTool;
import com.ubot.ai.tool.AiToolRegistry;
import com.ubot.ai.tool.StoreTools;
import com.ubot.llm.dto.request.LlmMessageRequestDto;
import com.ubot.llm.dto.request.LlmRequestDto;
import com.ubot.llm.enums.LlmMessageRole;
import com.ubot.llm.exception.LlmErrorCode;
import com.ubot.llm.exception.LlmException;
import com.ubot.location.service.LocationService;
import com.ubot.store.dto.NearbyStoreResponseDto;
import com.ubot.store.service.StoreService;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.ollama.api.OllamaChatOptions;

/** 도구가 있는 요청이 ChatClient 경로에서 실제로 도구를 실행하고 LLM을 다시 호출하는지 확인합니다. */
class OllamaClientToolCallingTest {

    private final ChatModel chatModel = mock(ChatModel.class);
    private final StoreService storeService = mock(StoreService.class);
    private final AiToolRegistry registry =
            new AiToolRegistry(new StoreTools(storeService, mock(LocationService.class)));

    private final LlmRequestDto request = new LlmRequestDto(List.of(
            new LlmMessageRequestDto(LlmMessageRole.SYSTEM, "시스템"),
            new LlmMessageRequestDto(LlmMessageRole.USER, "근처 매장 알려줘")))
            .withTools(registry.resolve(Set.of(AiTool.STORE_SEARCH)), Map.of(
                    StoreTools.QUESTION, "근처 매장 알려줘",
                    StoreTools.LATITUDE, 37.5,
                    StoreTools.LONGITUDE, 127.0));

    @BeforeEach
    void setUp() {
        // ChatClient는 모델의 기본 옵션을 복사해 도구 목록을 얹습니다.
        when(chatModel.getOptions()).thenReturn(OllamaChatOptions.builder().model("test-model").build());
        when(storeService.getNearbyStoreList(37.5, 127.0, 3.0, List.of(), 5)).thenReturn(List.of(
                new NearbyStoreResponseDto(12L, "강남점", "서울", "강남구", "서울 강남구 테헤란로 1",
                        "02-123-4567", "10:00~21:00", 37.49, 127.02, 0.42)));
    }

    @Test
    void executesToolAndCallsModelAgain() {
        when(chatModel.call(any(Prompt.class))).thenReturn(toolCallResponse(), textResponse("강남점이 가까워요."));

        var answer = new OllamaClient(chatModel, "test-model").generateAnswer(request);

        assertThat(answer.answer()).isEqualTo("강남점이 가까워요.");
        // toolContext의 좌표가 도구로 전달되어 매장을 조회합니다.
        verify(storeService).getNearbyStoreList(37.5, 127.0, 3.0, List.of(), 5);

        ArgumentCaptor<Prompt> prompts = ArgumentCaptor.forClass(Prompt.class);
        verify(chatModel, times(2)).call(prompts.capture());
        Prompt first = prompts.getAllValues().getFirst();
        assertThat(first.getOptions()).isInstanceOfSatisfying(OllamaChatOptions.class, options -> {
            assertThat(options.getModel()).isEqualTo("test-model");
            assertThat(options.getToolCallbacks()).extracting(callback -> callback.getToolDefinition().name())
                    .containsExactly("findNearbyStores");
        });
        // 두 번째 호출에는 도구 실행 결과가 대화에 붙어 있어야 합니다.
        Prompt second = prompts.getAllValues().get(1);
        assertThat(second.getInstructions().getLast()).isInstanceOfSatisfying(ToolResponseMessage.class,
                message -> assertThat(message.getResponses().getFirst().responseData())
                        .contains("[매장 ID: 12] 강남점"));
    }

    @Test
    void requestWithoutToolsKeepsDirectModelCall() {
        when(chatModel.call(any(Prompt.class))).thenReturn(textResponse("답변"));

        var answer = new OllamaClient(chatModel, "test-model").generateAnswer(new LlmRequestDto(List.of(
                new LlmMessageRequestDto(LlmMessageRole.USER, "질문"))));

        assertThat(answer.answer()).isEqualTo("답변");
        ArgumentCaptor<Prompt> prompt = ArgumentCaptor.forClass(Prompt.class);
        verify(chatModel).call(prompt.capture());
        // 기존 경로는 옵션 없이 호출하고, 모델 기본 옵션은 OllamaChatModel이 채웁니다.
        assertThat(prompt.getValue().getOptions()).isNull();
    }

    @Test
    void mapsModelFailureInToolPath() {
        when(chatModel.call(any(Prompt.class))).thenThrow(new IllegalStateException("down"));

        assertThatThrownBy(() -> new OllamaClient(chatModel, "test-model").generateAnswer(request))
                .isInstanceOfSatisfying(LlmException.class, exception -> assertThat(exception.getErrorCode())
                        .isEqualTo(LlmErrorCode.LLM_SERVICE_UNAVAILABLE));
    }

    private ChatResponse toolCallResponse() {
        AssistantMessage message = AssistantMessage.builder()
                .content("")
                .toolCalls(List.of(new AssistantMessage.ToolCall("call-1", "function", "findNearbyStores", "{}")))
                .build();
        return new ChatResponse(List.of(new Generation(message)));
    }

    private ChatResponse textResponse(String text) {
        return new ChatResponse(List.of(new Generation(new AssistantMessage(text))));
    }
}
