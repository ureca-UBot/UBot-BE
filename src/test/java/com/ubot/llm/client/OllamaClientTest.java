package com.ubot.llm.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.ubot.llm.dto.request.LlmMessageRequestDto;
import com.ubot.llm.dto.request.LlmRequestDto;
import com.ubot.llm.enums.LlmErrorCode;
import com.ubot.llm.enums.LlmMessageRole;
import com.ubot.llm.exception.LlmException;
import java.net.ConnectException;
import java.net.http.HttpTimeoutException;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.web.client.ResourceAccessException;

@ExtendWith(MockitoExtension.class)
class OllamaClientTest {

    @Mock
    private ChatModel chatModel;

    private final LlmRequestDto request = new LlmRequestDto(List.of(
            new LlmMessageRequestDto(LlmMessageRole.USER, "질문")));

    @Test
    void refusesMissingModelWithoutCallingServer() {
        var client = new OllamaClient(chatModel, " ");

        assertError(client, LlmErrorCode.LLM_MODEL_NOT_CONFIGURED);
        verifyNoInteractions(chatModel);
    }

    @ParameterizedTest
    @MethodSource("invalidResponses")
    void rejectsMissingOrEmptyAnswer(ChatResponse response) {
        when(chatModel.call(any(Prompt.class))).thenReturn(response);

        assertError(new OllamaClient(chatModel, "test-model"), LlmErrorCode.LLM_RESPONSE_INVALID);
    }

    static Stream<ChatResponse> invalidResponses() {
        return Stream.of(null, new ChatResponse(List.of()),
                new ChatResponse(List.of(new Generation(new AssistantMessage("")))),
                new ChatResponse(List.of(new Generation(new AssistantMessage(" \n ")))));
    }

    @Test
    void rejectsTruncatedAnswer() {
        var response = mock(ChatResponse.class);
        when(response.getResult()).thenReturn(new Generation(new AssistantMessage("미완성 답변")));
        when(response.hasFinishReasons(Set.of("length"))).thenReturn(true);
        when(chatModel.call(any(Prompt.class))).thenReturn(response);

        assertError(new OllamaClient(chatModel, "test-model"), LlmErrorCode.LLM_RESPONSE_INVALID);
    }

    @Test
    void rejectsToolCallsBecauseThisModuleOnlyGeneratesText() {
        var response = mock(ChatResponse.class);
        when(response.getResult()).thenReturn(new Generation(new AssistantMessage("도구 호출 요청")));
        when(response.hasToolCalls()).thenReturn(true);
        when(chatModel.call(any(Prompt.class))).thenReturn(response);

        assertError(new OllamaClient(chatModel, "test-model"), LlmErrorCode.LLM_RESPONSE_INVALID);
    }

    @Test
    void distinguishesTimeoutFromConnectionFailure() {
        when(chatModel.call(any(Prompt.class))).thenThrow(
                new ResourceAccessException("IO", new HttpTimeoutException("timeout")));
        assertError(new OllamaClient(chatModel, "test-model"), LlmErrorCode.LLM_TIMEOUT);
    }

    @Test
    void mapsRefusedConnectionToUnavailable() {
        when(chatModel.call(any(Prompt.class))).thenThrow(
                new ResourceAccessException("IO", new ConnectException("refused")));
        assertError(new OllamaClient(chatModel, "test-model"), LlmErrorCode.LLM_SERVICE_UNAVAILABLE);
    }

    private void assertError(OllamaClient client, LlmErrorCode expected) {
        assertThatThrownBy(() -> client.generateAnswer(request))
                .isInstanceOfSatisfying(LlmException.class,
                        exception -> assertThat(exception.getErrorCode()).isEqualTo(expected));
    }
}
