package com.ubot.ai.service;

import com.ubot.ai.dto.AiAnswer;
import com.ubot.ai.dto.AnswerMaterials;
import com.ubot.ai.dto.ConversationTurn;
import com.ubot.ai.dto.OutputGuardResult;
import com.ubot.ai.dto.StoreMapResult;
import com.ubot.ai.tool.AiToolRegistry;
import com.ubot.ai.tool.StoreSearchRecorder;
import com.ubot.ai.tool.StoreTools;
import com.ubot.llm.dto.request.LlmRequestDto;
import com.ubot.llm.dto.response.LlmResponseDto;
import com.ubot.llm.exception.LlmErrorCode;
import com.ubot.llm.exception.LlmException;
import com.ubot.llm.service.LlmService;
import com.ubot.prompt.exception.PromptErrorCode;
import com.ubot.prompt.exception.PromptException;
import com.ubot.prompt.format.FaqAnswerFormat;
import com.ubot.prompt.format.FaqAnswerFormat.FaqAnswer;
import com.ubot.prompt.service.PromptService;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Slf4j
@Service
@RequiredArgsConstructor
public class AiService {

    private final PromptService promptService;
    private final LlmService llmService;
    private final AiToolRegistry toolRegistry;
    private final AiOutputGuardService aiOutputGuardService;

    public AiAnswer generateAnswer(AnswerMaterials materials) {
        // ChatAnswerProcessor가 intent별로 모은 자료를 받습니다. DB를 다시 조회하지 않습니다.
    	
    	// LLM에게 줄 자료(FAQ·추가 자료·도구)가 하나도 없으면 호출하지 않습니다. storeMap은 화면용이라 자료로 세지 않습니다.
        if (materials.faqs().isEmpty() && materials.sections().isEmpty() && materials.tools().isEmpty()) {
            throw new PromptException(PromptErrorCode.PROMPT_INPUT_MISSING);
        }
        // 승지님이 작성한 프롬프트에 질문·FAQ·추가 자료를 넣어 메시지를 구성합니다.
        // 이전 대화는 이번 질문이 이전 대화를 써야 할 때만 들어 있습니다(SubQuestionPlanner의 멀티턴 판정).
        LlmRequestDto request = promptService.createPrompt(materials.question(), materials.faqs(),
                materials.subQuestions(), materials.sections(), materials.history());

        // 도구가 조회한 매장 목록은 LLM 답변 문장과 별도로 화면에 전달하기 위해 요청마다 새로 보관합니다.
        StoreSearchRecorder storeRecorder = new StoreSearchRecorder();
        List<ToolCallback> callbacks = toolRegistry.resolve(materials.tools());
        if (!callbacks.isEmpty()) {
            request = request.withTools(callbacks, toToolContext(materials, storeRecorder));
        }

        // 프롬프트가 준비됐을 때만 LLM을 호출하고, 생성된 답변을 채팅 쪽으로 반환합니다.
        // 빈 응답 판단은 ChatAnswerProcessor가 합니다.
        LlmResponseDto response = llmService.generateAnswer(request);
        // 기존 파서와 출력 가드로 한 번 검사하고, 후보 선별에는 상태가 아닌 실제 검사 결과를 전달합니다.
        StoreMapResult storeMap = storeRecorder.result().orElse(materials.storeMap());
        String answer = null;
        String status = null;
        OutputGuardResult inspection = null;
        if (response != null && StringUtils.hasText(response.answer())) {
            FaqAnswer parsed = FaqAnswerFormat.parse(response.answer());
            log.info("LLM 답변을 파싱했습니다: status={}, evidenceIds={}", parsed.status(), parsed.evidenceIds());
            inspection = aiOutputGuardService.inspectAnswer(parsed.answer(), parsed.evidenceIds(), materials, storeMap);
            // 내부 결과를 사용해도 기존 전체 차단 오류와 자동 재시도 금지 정책은 유지합니다.
            if (inspection.rejected()) throw new LlmException(LlmErrorCode.LLM_OUTPUT_REJECTED);
            answer = inspection.answer();
            status = parsed.status();
        }
        return new AiAnswer(answer, storeMap, storeRecorder.isLocationRequired(), status, inspection);
    }

    private Map<String, Object> toToolContext(AnswerMaterials materials, StoreSearchRecorder storeRecorder) {
        Map<String, Object> context = new HashMap<>();
        // 비어 있으면 Spring AI가 도구 실행 시 예외를 던지므로 질문 원문은 항상 넣습니다. (place 검증에도 사용)
        // "거기 영업시간은?"처럼 장소가 이전 대화에만 있으면 답변 LLM에 넘긴 이전 대화(사용자 질문·답변)를 함께 넣습니다.
        // 판정 LLM이 다시 쓴 검색어는 넣지 않습니다. 검색어에 지어낸 장소가 있으면 도구가 사용자가 말한 장소로 오인합니다.
        StringBuilder question = new StringBuilder(materials.question());
        for (ConversationTurn turn : materials.history()) {
            question.append('\n').append(turn.question());
            if (StringUtils.hasText(turn.answer())) {
                question.append('\n').append(turn.answer());
            }
        }
        context.put(StoreTools.QUESTION, question.toString());
        context.put(StoreTools.RECORDER, storeRecorder);
        
        return context;
    }
}
