package com.ubot.llm.client;

import com.ubot.llm.dto.request.LlmMessageRequestDto;
import com.ubot.llm.dto.request.LlmRequestDto;
import com.ubot.llm.dto.response.LlmResponseDto;
import com.ubot.llm.exception.LlmErrorCode;
import com.ubot.llm.exception.LlmException;

import io.micrometer.observation.ObservationRegistry;

import java.net.SocketTimeoutException;
import java.net.http.HttpTimeoutException;
import java.util.Set;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.ToolCallingAdvisor;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.DefaultToolCallingManager;
import org.springframework.ai.model.tool.ToolCallLimitBehavior;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.http.converter.HttpMessageConversionException;
import org.springframework.util.StringUtils;

public class OllamaClient implements LlmClient {

	private final ChatModel chatModel;
    private final ChatClient chatClient;
    private final String modelName;
    
    // LlmConfig는 수정하지 않기로 했으므로, 한도 횟수를 설정값으로 빼지는 않는다.
    private static final int MAX_CALLS_PER_TOOL = 3;

    public OllamaClient(ChatModel chatModel, String modelName) {
        this.chatModel = chatModel;
        
        // LLM이 같은 도구를 계속 다시 부르는 경우를 제한. Spring AI 기본값(도구당 40번)은 응답 시간에 비해 너무 크다.
        // 한도를 넘으면 도구를 실행하지 않고 "한도 초과"를 도구 결과로 알려, LLM이 그걸 보고 답변을 마무리하게 합니다.
        ToolCallingManager toolCallingManager = DefaultToolCallingManager.builder()
                .maxCallsPerTool(MAX_CALLS_PER_TOOL)
                .onLimitExceeded(ToolCallLimitBehavior.RETURN_ERROR_RESPONSE)
                .build();
        // 같은 chatModel을 감싸므로 LlmConfig의 타임아웃·재시도 설정이 그대로 적용됩니다.
        this.chatClient = ChatClient.builder(chatModel, ObservationRegistry.NOOP, null, null,
                ToolCallingAdvisor.builder().toolCallingManager(toolCallingManager))
        		.build();
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
            // chatModel.call()은 도구 정의만 보내고 실행하지 않으므로, 도구가 있으면 ChatClient의
            // ToolCallingAdvisor가 도구 실행과 재호출을 반복하게 합니다.
            response = request.hasTools()
                    ? chatClient.prompt(new Prompt(messages))
                            .tools(request.toolCallbacks())
                            .toolContext(request.toolContext())
                            .call()
                            .chatResponse()
                    : chatModel.call(new Prompt(messages));
        } catch (RuntimeException exception) {
            throw translateException(exception);
        }

        // 빈 결과·길이 제한으로 잘린 결과·도구 실행이 끝난 뒤에도 남은 도구 호출은 정상 답변으로 쓰지 않습니다.
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

    @Override
    public String getModelName() {
        return modelName;
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
