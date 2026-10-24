package com.ubot.llm.client;

import com.ubot.llm.dto.request.LlmMessageRequestDto;
import com.ubot.llm.dto.request.LlmRequestDto;
import com.ubot.llm.dto.response.LlmResponseDto;
import com.ubot.llm.exception.LlmErrorCode;
import com.ubot.llm.exception.LlmException;

import java.net.SocketTimeoutException;
import java.net.http.HttpTimeoutException;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CancellationException;
import lombok.extern.slf4j.Slf4j;
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
import org.springframework.ai.model.tool.ToolExecutionResult;
import org.springframework.ai.ollama.OllamaChatModel;
import org.springframework.ai.ollama.api.OllamaChatOptions;
import org.springframework.http.converter.HttpMessageConversionException;
import org.springframework.util.StringUtils;

@Slf4j
public class OllamaClient implements LlmClient {

    private final ChatModel chatModel;
    private final ToolCallingManager toolCallingManager;
    private final String modelName;
    
    // LlmConfig는 수정하지 않기로 했으므로, 한도 횟수를 설정값으로 빼지는 않는다.
    private static final int MAX_CALLS_PER_TOOL = 3;
    private static final int MAX_TOOL_ROUNDS = 8;

    public OllamaClient(ChatModel chatModel, String modelName) {
        this.chatModel = chatModel;
        
        // LLM이 같은 도구를 계속 다시 부르는 경우를 제한. Spring AI 기본값(도구당 40번)은 응답 시간에 비해 너무 크다.
        // 한도를 넘으면 도구를 실행하지 않고 "한도 초과"를 도구 결과로 알려, LLM이 그걸 보고 답변을 마무리하게 합니다.
        this.toolCallingManager = DefaultToolCallingManager.builder()
                .maxCallsPerTool(MAX_CALLS_PER_TOOL)
                .onLimitExceeded(ToolCallLimitBehavior.RETURN_ERROR_RESPONSE)
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
            // 도구 단계의 대화를 보관해 두고, 최종 형식이 잘못됐을 때만 도구 없이 스키마 재호출합니다.
            response = request.hasTools()
                    ? callWithTools(messages, request)
                    : chatModel.call(createPrompt(messages, request));
        } catch (RuntimeException exception) {
            if (Thread.currentThread().isInterrupted()) {
                throw new CancellationException("LLM 요청이 취소되었습니다.");
            }
            if (exception instanceof CancellationException cancelled) {
                throw cancelled;
            }
            if (exception instanceof LlmException llmException) {
                throw llmException;
            }
            throw translateException(exception);
        }

        // 빈 결과·길이 제한으로 잘린 결과·도구 실행이 끝난 뒤에도 남은 도구 호출은 정상 답변으로 쓰지 않습니다.
        validateResponse(response);

        // 모델의 별도 thinking 정보는 제외하고 최종 답변 문자열만 꺼냅니다.
        String answer = response.getResult().getOutput().getText();
        if (!StringUtils.hasText(answer)) {
            log.warn("LLM 응답을 쓸 수 없습니다: 제공자=ollama, 사유=빈 답변, 종료사유={}", finishReason(response));
            throw new LlmException(LlmErrorCode.LLM_RESPONSE_INVALID);
        }
        return new LlmResponseDto(answer);
    }

    @Override
    public String getModelName() {
        return modelName;
    }

    private ChatResponse callWithTools(List<Message> messages, LlmRequestDto request) {
        OllamaChatOptions defaultOptions;
        if (chatModel.getOptions() instanceof OllamaChatOptions options) {
            defaultOptions = options;
        } else {
            defaultOptions = OllamaChatOptions.builder()
                    .model(modelName)
                    .temperature(LlmClient.TEMPERATURE)
                    .build();
        }

        // 1. 도구를 사용할 동안에는 스키마를 걸지 않습니다.
        OllamaChatOptions toolOptions = defaultOptions.mutate()
                .format(null)
                .toolCallbacks(request.toolCallbacks())
                .toolContext(request.toolContext())
                .build();
        List<Message> conversationHistory = messages;
        ChatResponse response = chatModel.call(new Prompt(conversationHistory, toolOptions));
        int toolRounds = 0;
        while (response != null && response.hasToolCalls()) {
            checkInterrupted();
            toolRounds++;
            if (toolRounds > MAX_TOOL_ROUNDS) {
                log.warn("LLM 응답을 쓸 수 없습니다: 제공자=ollama, 사유=도구 호출 {}회 초과", MAX_TOOL_ROUNDS);
                throw new LlmException(LlmErrorCode.LLM_RESPONSE_INVALID);
            }

            ToolExecutionResult toolResult = toolCallingManager.executeToolCalls(
                    new Prompt(conversationHistory, toolOptions), response);
            conversationHistory = toolResult.conversationHistory();
            if (toolResult.returnDirect()) {
                // 결과를 그대로 답변으로 쓰는 도구는 LLM을 다시 부르지 않고 형식 재호출도 하지 않습니다.
                return new ChatResponse(ToolExecutionResult.buildGenerations(toolResult));
            }
            response = chatModel.call(new Prompt(conversationHistory, toolOptions));
        }

        // 2. 도구 단계가 끝났습니다. 응답 없음·길이 제한은 재호출하지 않고 기존 오류로 처리합니다.
        validateResponse(response);
        if (!request.hasResponseSchema()) {
            return response;
        }
        String answer = response.getResult().getOutput().getText();
        String formatError = ResponseSchemaValidator.violation(answer, request.responseSchema());
        log.info("LLM 최종 답변 형식을 검사했습니다: 제공자=ollama, 재호출={}, 형식오류={}",
                formatError != null, formatError);
        if (formatError == null) {
            return response;
        }

        // 3. 형식이 틀렸을 때만 한 번 다시 생성합니다. 도구 실행 이력은 유지하고, 기존 답변은 넣지 않습니다.
        checkInterrupted();
        OllamaChatOptions schemaOptions = defaultOptions.mutate()
                .toolCallbacks(List.of())
                .toolContext(Map.of())
                .format(request.responseSchema())
                .build();
        ChatResponse schemaResponse = chatModel.call(new Prompt(conversationHistory, schemaOptions));
        validateResponse(schemaResponse);

        String schemaAnswer = schemaResponse.getResult().getOutput().getText();
        String remainingError = ResponseSchemaValidator.violation(schemaAnswer, request.responseSchema());
        log.info("LLM 스키마 재호출 결과를 검사했습니다: 제공자=ollama, 형식오류={}", remainingError);
        if (remainingError != null) {
            throw new LlmException(LlmErrorCode.LLM_RESPONSE_INVALID);
        }
        return schemaResponse;
    }

    private void validateResponse(ChatResponse response) {
        String reason = response == null ? "응답 없음"
                : response.getResult() == null || response.getResult().getOutput() == null ? "결과 없음"
                : response.hasToolCalls() ? "도구 호출이 남음"
                : response.hasFinishReasons(Set.of("length")) ? "길이 제한으로 잘림"
                : null;
        if (reason != null) {
            // LLM-005는 원인이 여러 가지라 실패 사유를 남겨 둡니다.
            log.warn("LLM 응답을 쓸 수 없습니다: 제공자=ollama, 사유={}, 종료사유={}", reason, finishReason(response));
            throw new LlmException(LlmErrorCode.LLM_RESPONSE_INVALID);
        }
    }

    private static String finishReason(ChatResponse response) {
        return response == null || response.getResult() == null || response.getResult().getMetadata() == null ? null
                : response.getResult().getMetadata().getFinishReason();
    }

    private void checkInterrupted() {
        if (Thread.currentThread().isInterrupted()) {
            throw new CancellationException("LLM 요청이 취소되었습니다.");
        }
    }

    private Prompt createPrompt(List<Message> messages, LlmRequestDto request) {
        // 요청에 옵션을 넣으면 OllamaChatModel은 기본 옵션과 합치지 않고 요청 옵션만 씁니다.
        // 그래서 기본 옵션(모델·temperature)을 복사한 뒤 출력 스키마(format)만 더합니다.
        if (request.hasResponseSchema() && chatModel instanceof OllamaChatModel ollamaChatModel) {
            OllamaChatOptions options = ollamaChatModel.getOptions().mutate()
                    .format(request.responseSchema())
                    .build();
            return new Prompt(messages, options);
        }
        return new Prompt(messages);
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
                log.warn("LLM 응답을 쓸 수 없습니다: 제공자=ollama, 사유=응답 해석 실패, 원인={}", cause.toString());
                return new LlmException(LlmErrorCode.LLM_RESPONSE_INVALID, exception);
            }
        }
        return new LlmException(LlmErrorCode.LLM_SERVICE_UNAVAILABLE, exception);
    }
}
