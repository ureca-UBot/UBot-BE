package com.ubot.ai.service;

import com.ubot.ai.dto.AiAnswer;
import com.ubot.ai.dto.AnswerMaterials;
import com.ubot.ai.tool.AiToolRegistry;
import com.ubot.ai.tool.StoreSearchRecorder;
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

    public AiAnswer generateAnswer(AnswerMaterials materials) {
        // ChatService가 intent별로 모은 자료를 받습니다. DB를 다시 조회하지 않습니다.
        if (materials.faqs().isEmpty() && materials.sections().isEmpty() && materials.tools().isEmpty()) {
            throw new PromptException("답변 생성에 필요한 자료가 없습니다.");
        }
        // 승지님이 작성한 프롬프트에 질문·FAQ·추가 자료를 넣어 메시지를 구성합니다.
        LlmRequestDto request = promptService.createPrompt(
                materials.question(), materials.faqs(), materials.sections());

        // 도구가 조회한 매장 목록은 LLM 답변 문장과 별도로 화면에 전달하기 위해 요청마다 새로 보관합니다.
        StoreSearchRecorder storeRecorder = new StoreSearchRecorder();
        List<ToolCallback> callbacks = toolRegistry.resolve(materials.tools());
        if (!callbacks.isEmpty()) {
            request = request.withTools(callbacks, toToolContext(materials, storeRecorder));
        }

        // 프롬프트가 준비됐을 때만 LLM을 호출하고, 생성된 답변을 채팅 쪽으로 반환합니다.
        // 빈 응답 판단은 기존처럼 ChatService가 합니다.
        LlmResponseDto response = llmService.generateAnswer(request);
        return new AiAnswer(response == null ? null : response.answer(), storeRecorder.result().orElse(null));
    }

    private Map<String, Object> toToolContext(AnswerMaterials materials, StoreSearchRecorder storeRecorder) {
        Map<String, Object> context = new HashMap<>();
        // 비어 있으면 Spring AI가 도구 실행 시 예외를 던지므로 질문 원문은 항상 넣습니다. (place 검증에도 사용)
        context.put(StoreTools.QUESTION, materials.question());
        context.put(StoreTools.RECORDER, storeRecorder);
        if (materials.location() != null) {
            context.put(StoreTools.LATITUDE, materials.location().latitude());
            context.put(StoreTools.LONGITUDE, materials.location().longitude());
        }
        return context;
    }
}
