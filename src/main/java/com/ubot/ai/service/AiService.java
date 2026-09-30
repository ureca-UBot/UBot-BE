package com.ubot.ai.service;

import com.ubot.ai.dto.AnswerMaterials;
import com.ubot.ai.tool.AiToolRegistry;
import com.ubot.ai.tool.StoreTools;
import com.ubot.llm.dto.request.LlmRequestDto;
import com.ubot.llm.dto.response.LlmResponseDto;
import com.ubot.llm.service.LlmService;
import com.ubot.prompt.exception.PromptException;
import com.ubot.prompt.service.PromptService;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class AiService {

    private final PromptService promptService;
    private final LlmService llmService;
    private final AiToolRegistry toolRegistry;

    public LlmResponseDto generateAnswer(AnswerMaterials materials) {
        // ChatService가 intent별로 모은 자료를 받습니다. DB를 다시 조회하지 않습니다.
        if (materials.faqs().isEmpty() && materials.sections().isEmpty() && materials.tools().isEmpty()) {
            throw new PromptException("답변 생성에 필요한 자료가 없습니다.");
        }
        // 승지님이 작성한 프롬프트에 질문·FAQ·추가 자료를 넣어 메시지를 구성합니다.
        LlmRequestDto request = promptService.createPrompt(
                materials.question(), materials.faqs(), materials.sections());

        List<ToolCallback> callbacks = toolRegistry.resolve(materials.tools());
        if (!callbacks.isEmpty()) {
            request = request.withTools(callbacks, toToolContext(materials));
        }

        // 프롬프트가 준비됐을 때만 LLM을 호출하고, 생성된 답변을 채팅 쪽으로 반환합니다.
        return llmService.generateAnswer(request);
    }

    private Map<String, Object> toToolContext(AnswerMaterials materials) {
        Map<String, Object> context = new HashMap<>();
        // 비어 있으면 Spring AI가 도구 실행 시 예외를 던지므로 질문 원문은 항상 넣습니다. (place 검증에도 사용)
        context.put(StoreTools.QUESTION, materials.question());
        if (materials.location() != null) {
            context.put(StoreTools.LATITUDE, materials.location().latitude());
            context.put(StoreTools.LONGITUDE, materials.location().longitude());
        }
        return context;
    }
}
