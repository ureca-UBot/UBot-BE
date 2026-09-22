package com.ubot.llm.client;

import com.ubot.llm.dto.request.LlmMessageRequestDto;
import com.ubot.llm.dto.request.LlmRequestDto;
import com.ubot.llm.dto.response.LlmResponseDto;
import com.ubot.llm.enums.LlmErrorCode;
import com.ubot.llm.exception.LlmException;
import java.net.SocketTimeoutException;
import java.net.http.HttpTimeoutException;
import java.util.Set;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.http.converter.HttpMessageConversionException;
import org.springframework.util.StringUtils;

public class OllamaClient implements LlmClient {

    private final ChatModel chatModel;
    private final String modelName;

    public OllamaClient(ChatModel chatModel, String modelName) {
        this.chatModel = chatModel;
        this.modelName = modelName;
    }

    @Override
    public LlmResponseDto generateAnswer(LlmRequestDto request) {
        if (!StringUtils.hasText(modelName)) {
            throw new LlmException(LlmErrorCode.LLM_MODEL_NOT_CONFIGURED);
        }

        // 우리 메시지 DTO를 Spring AI가 사용하는 메시지 객체로 변환합니다.
        var messages = request.messages().stream().map(this::toMessage).toList();
        ChatResponse response;
        try {
            // 완성된 답변을 한 번에 받습니다. 현재 채팅 API는 SSE 스트리밍을 사용하지 않습니다.
            response = chatModel.call(new Prompt(messages));
        } catch (RuntimeException exception) {
            throw translateException(exception);
        }

        // 빈 결과·길이 제한으로 잘린 결과·지원하지 않는 도구 호출은 정상 답변으로 쓰지 않습니다.
        if (response == null || response.getResult() == null
                || response.getResult().getOutput() == null || response.hasToolCalls()
                || response.hasFinishReasons(Set.of("length"))) {
            throw new LlmException(LlmErrorCode.LLM_RESPONSE_INVALID);
        }

        // 모델의 별도 thinking 정보는 제외하고 최종 답변 문자열만 꺼냅니다.
        String answer = response.getResult().getOutput().getText();
        if (!StringUtils.hasText(answer)) {
            throw new LlmException(LlmErrorCode.LLM_RESPONSE_INVALID);
        }
        return new LlmResponseDto(answer);
    }

    private Message toMessage(LlmMessageRequestDto message) {
        return switch (message.role()) {
            case SYSTEM -> new SystemMessage(message.content());
            case USER -> new UserMessage(message.content());
            case ASSISTANT -> new AssistantMessage(message.content());
        };
    }

    private LlmException translateException(RuntimeException exception) {
        for (Throwable cause = exception; cause != null; cause = cause.getCause()) {
            if (cause instanceof HttpTimeoutException || cause instanceof SocketTimeoutException) {
                return new LlmException(LlmErrorCode.LLM_TIMEOUT, exception);
            }
            if (cause instanceof HttpMessageConversionException) {
                return new LlmException(LlmErrorCode.LLM_RESPONSE_INVALID, exception);
            }
        }
        return new LlmException(LlmErrorCode.LLM_SERVICE_UNAVAILABLE, exception);
    }
}
