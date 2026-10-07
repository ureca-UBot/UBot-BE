package com.ubot.llm.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.ubot.llm.client.LlmClient;
import com.ubot.llm.dto.request.LlmMessageRequestDto;
import com.ubot.llm.dto.request.LlmRequestDto;
import com.ubot.llm.dto.response.LlmResponseDto;
import com.ubot.llm.exception.LlmErrorCode;
import com.ubot.llm.enums.LlmMessageRole;
import com.ubot.llm.exception.LlmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.tool.ToolCallback;

@ExtendWith(MockitoExtension.class)
class LlmServiceTest {

    @Mock
    private LlmClient llmClient;

    @InjectMocks
    private LlmService llmService;

    @Test
    void generateAnswerReturnsGeneratedAnswer() {
        var request = new LlmRequestDto(List.of(
                new LlmMessageRequestDto(LlmMessageRole.SYSTEM, "제공된 FAQ로 답변하세요."),
                new LlmMessageRequestDto(LlmMessageRole.USER, "유심 재발급은 어떻게 하나요?")));
        when(llmClient.generateAnswer(request)).thenReturn(new LlmResponseDto("가까운 매장을 방문해주세요."));

        assertThat(llmService.generateAnswer(request).answer()).isEqualTo("가까운 매장을 방문해주세요.");
    }

    @Test
    void getModelNameReturnsModelNameOfSelectedClient() {
        when(llmClient.getModelName()).thenReturn("ubot-chat");

        assertThat(llmService.getModelName()).isEqualTo("ubot-chat");
    }

    @Test
    void rejectsToolsWithoutToolContext() {
        var request = new LlmRequestDto(List.of(new LlmMessageRequestDto(LlmMessageRole.USER, "근처 매장")))
                .withTools(List.of(mock(ToolCallback.class)), Map.of());

        assertThatThrownBy(() -> llmService.generateAnswer(request))
                .isInstanceOfSatisfying(LlmException.class,
                        exception -> assertThat(exception.getErrorCode()).isEqualTo(LlmErrorCode.LLM_REQUEST_INVALID));
        verifyNoInteractions(llmClient);
    }

    @Test
    void passesToolsWithToolContextToClient() {
        var request = new LlmRequestDto(List.of(new LlmMessageRequestDto(LlmMessageRole.USER, "근처 매장")))
                .withTools(List.of(mock(ToolCallback.class)), Map.of("question", "근처 매장"));
        when(llmClient.generateAnswer(request)).thenReturn(new LlmResponseDto("답변"));

        assertThat(llmService.generateAnswer(request).answer()).isEqualTo("답변");
    }

    @ParameterizedTest
    @MethodSource("invalidRequests")
    void rejectsInvalidMessagesBeforeCallingModel(LlmRequestDto request) {
        assertThatThrownBy(() -> llmService.generateAnswer(request))
                .isInstanceOfSatisfying(LlmException.class,
                        exception -> assertThat(exception.getErrorCode()).isEqualTo(LlmErrorCode.LLM_REQUEST_INVALID));
        verifyNoInteractions(llmClient);
    }

    static Stream<LlmRequestDto> invalidRequests() {
        return Stream.of(
                null,
                new LlmRequestDto(null),
                new LlmRequestDto(List.of()),
                new LlmRequestDto(Arrays.asList((LlmMessageRequestDto) null)),
                new LlmRequestDto(List.of(new LlmMessageRequestDto(null, "질문"))),
                new LlmRequestDto(List.of(new LlmMessageRequestDto(LlmMessageRole.USER, null))),
                new LlmRequestDto(List.of(new LlmMessageRequestDto(LlmMessageRole.USER, " \n "))),
                new LlmRequestDto(List.of(new LlmMessageRequestDto(LlmMessageRole.SYSTEM, "규칙만 존재"))),
                new LlmRequestDto(List.of(new LlmMessageRequestDto(LlmMessageRole.ASSISTANT, "이전 답변만 존재"))));
    }

    @Test
    void requestKeepsSnapshotOfMessages() {
        var messages = new ArrayList<>(List.of(new LlmMessageRequestDto(LlmMessageRole.USER, "첫 질문")));
        var request = new LlmRequestDto(messages);
        messages.clear();

        assertThat(request.messages()).hasSize(1);
        assertThatThrownBy(() -> request.messages().clear()).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void preservesClientFailureForCaller() {
        var request = new LlmRequestDto(List.of(new LlmMessageRequestDto(LlmMessageRole.USER, "질문")));
        var failure = new LlmException(LlmErrorCode.LLM_TIMEOUT);
        when(llmClient.generateAnswer(request)).thenThrow(failure);

        assertThatThrownBy(() -> llmService.generateAnswer(request)).isSameAs(failure);
    }
}
