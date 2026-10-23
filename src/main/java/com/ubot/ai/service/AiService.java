package com.ubot.ai.service;

import com.ubot.ai.dto.AiAnswer;
import com.ubot.ai.dto.AnswerMaterials;
import com.ubot.ai.dto.ContextSection;
import com.ubot.ai.dto.ConversationTurn;
import com.ubot.ai.dto.OutputGuardResult;
import com.ubot.ai.dto.StoreMapResult;
import com.ubot.ai.dto.SubQuestionFaqs;
import com.ubot.ai.dto.UserDataCards;
import com.ubot.ai.tool.AiToolRegistry;
import com.ubot.ai.tool.StoreSearchRecorder;
import com.ubot.ai.tool.StoreTools;
import com.ubot.ai.tool.UserDataCardSelector;
import com.ubot.ai.tool.UserDataRecorder;
import com.ubot.ai.tool.UserDataTools;
import com.ubot.llm.dto.request.LlmRequestDto;
import com.ubot.llm.dto.response.LlmResponseDto;
import com.ubot.llm.exception.LlmErrorCode;
import com.ubot.llm.exception.LlmException;
import com.ubot.llm.service.LlmService;
import com.ubot.prompt.exception.PromptErrorCode;
import com.ubot.prompt.exception.PromptException;
import com.ubot.prompt.format.FaqAnswerFormat;
import com.ubot.prompt.format.FaqAnswerFormat.FaqAnswer;
import com.ubot.prompt.format.FaqAnswerFormat.SlotAnswer;
import com.ubot.prompt.service.PromptService;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
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
        // 도구가 있는 요청은 출력 스키마가 강제되지 않아 칸 수를 보장할 수 없으므로, 칸 형식을 쓰지 않고 기존 형식 그대로 둡니다.
        // 도구가 없는 요청만 하위 질문마다 칸(answer → status)을 받습니다.
        List<ToolCallback> callbacks = toolRegistry.resolve(materials.tools());
        boolean slotMode = callbacks.isEmpty();

        // 승지님이 작성한 프롬프트에 질문·FAQ·추가 자료를 넣어 메시지를 구성합니다.
        // 이전 대화는 이번 질문이 이전 대화를 써야 할 때만 들어 있습니다(SubQuestionPlanner의 멀티턴 판정).
        LlmRequestDto request = slotMode
                ? promptService.createSlotPrompt(materials.question(), materials.faqs(),
                        materials.subQuestions(), materials.sections(), materials.history())
                : promptService.createPrompt(materials.question(), materials.faqs(),
                        materials.subQuestions(), materials.sections(), materials.history());

        // 도구가 조회한 매장 목록은 LLM 답변 문장과 별도로 화면에 전달하기 위해 요청마다 새로 보관합니다.
        StoreSearchRecorder storeRecorder = new StoreSearchRecorder();
        // 가입 정보 도구가 조회한 사용량·청구서도 같은 이유로 요청마다 따로 보관합니다.
        UserDataRecorder userDataRecorder = new UserDataRecorder();
        if (!callbacks.isEmpty()) {
            request = request.withTools(callbacks, toToolContext(materials, storeRecorder, userDataRecorder));
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
            // 도구로 조회한 지난 달 기록은 프롬프트 자료에 없으므로, 출력 검사가 그 값도 근거로 보도록 사용자 정보로 덧붙입니다.
            String toolRecords = userDataRecorder.describe();
            AnswerMaterials inspected = toolRecords.isEmpty() ? materials
                    : materials.withToolRecords(new ContextSection(UserDataRecorder.SECTION_TITLE, toolRecords),
                            userDataRecorder.cards());
            if (slotMode && parsed.slots() != null) {
                SlotResult slotResult = answerBySlots(parsed, inspected, storeMap);
                answer = slotResult.answer();
                status = slotResult.status();
                inspection = slotResult.inspection();
            } else {
                inspection = aiOutputGuardService.inspectAnswer(parsed.answer(), parsed.evidenceIds(), inspected, storeMap);
                // 내부 결과를 사용해도 기존 전체 차단 오류와 자동 재시도 금지 정책은 유지합니다.
                if (inspection.rejected()) throw new LlmException(LlmErrorCode.LLM_OUTPUT_REJECTED);
                answer = inspection.answer();
                status = parsed.status();
            }
        }
        // 가입 정보 카드는 도구로 조회한 기록과 미리 넣은 요약 중, 답변에 쓰인 기록만 합칩니다.
        // 도구로 조회만 하고 답변에 쓰지 않은 달은 붙이지 않습니다.
        // 모델이 답을 보류했으면 조회한 기록이 있어도 카드를 붙이지 않습니다. 미응답 판단에 쓰도록 status는 그대로 둡니다.
        UserDataCards userData = "ABSTAIN".equals(status) ? null
                : UserDataCards.merge(
                        UserDataCardSelector.selectRecorded(materials.question(), answer, userDataRecorder.cards()),
                        UserDataCardSelector.select(materials.question(), answer, materials.userDataSummary()));
        return new AiAnswer(answer, storeMap, storeRecorder.isLocationRequired(), status, inspection, userData);
    }

    /**
     * 칸 형식 출력에서 ANSWER인 칸의 answer만 이어 출력 가드로 검사하고, 답하지 못한 하위 질문의 고지는 코드가 붙입니다.
     * 전체 status는 하위 status를 조합한 값입니다. 모든 칸이 ANSWER가 아니면 본문이 없으므로 가드를 거치지 않고 고지만 냅니다.
     * 칸 수가 맞지 않거나 알 수 없는 status가 있으면 어느 칸을 보여 줄지 정할 수 없으므로 응답 오류로 처리합니다.
     */
    private SlotResult answerBySlots(FaqAnswer parsed, AnswerMaterials materials, StoreMapResult storeMap) {
        List<SlotAnswer> slots = parsed.slots();
        int expected = PromptService.slotCount(materials.subQuestions());
        if (slots.size() != expected || slots.stream().anyMatch(slot -> slot.status() == null)) {
            log.warn("LLM 칸 형식 답변이 하위 질문과 맞지 않습니다: 칸수={}, 하위질문칸수={}, statuses={}",
                    slots.size(), expected, slots.stream().map(SlotAnswer::status).toList());
            throw new LlmException(LlmErrorCode.LLM_RESPONSE_INVALID);
        }
        // ANSWER인데 answer가 비었으면 그 하위 질문은 사용자에게 아무 답도 나가지 않으므로 ABSTAIN으로 봅니다.
        List<String> statuses = slots.stream()
                .map(slot -> SubQuestionStatuses.ANSWER.equals(slot.status()) && !StringUtils.hasText(slot.answer())
                        ? SubQuestionStatuses.ABSTAIN : slot.status())
                .toList();
        log.info("LLM 칸 형식 답변을 파싱했습니다: statuses={}, evidenceIds={}", statuses, parsed.evidenceIds());

        String body = IntStream.range(0, slots.size())
                .filter(index -> SubQuestionStatuses.ANSWER.equals(statuses.get(index)))
                .mapToObj(index -> slots.get(index).answer())
                .collect(Collectors.joining("\n\n"));
        OutputGuardResult inspection = null;
        if (StringUtils.hasText(body)) {
            inspection = aiOutputGuardService.inspectAnswer(body, parsed.evidenceIds(), materials, storeMap);
            // 내부 결과를 사용해도 기존 전체 차단 오류와 자동 재시도 금지 정책은 유지합니다.
            if (inspection.rejected()) throw new LlmException(LlmErrorCode.LLM_OUTPUT_REJECTED);
            body = inspection.answer();
        }
        // 칸이 하나면 질문 전체에 대한 고지이므로 원문 조각이 필요 없습니다.
        List<String> labels = expected > 1
                ? materials.subQuestions().stream().map(SubQuestionFaqs::userText).toList() : List.of();
        String notice = SubQuestionStatuses.notice(statuses, labels);
        return new SlotResult(SubQuestionStatuses.compose(body, notice), SubQuestionStatuses.combine(statuses), inspection);
    }

    private record SlotResult(String answer, String status, OutputGuardResult inspection) {
    }

    private Map<String, Object> toToolContext(AnswerMaterials materials, StoreSearchRecorder storeRecorder,
            UserDataRecorder userDataRecorder) {
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
        context.put(UserDataTools.RECORDER, userDataRecorder);
        // 가입 정보 도구는 LLM이 아니라 서버가 넣은 사용자 ID로만 조회합니다. 비회원이면 넣지 않습니다.
        if (materials.userId() != null) {
            context.put(UserDataTools.USER_ID, materials.userId());
        }
        // 요약이 실패하면 사용자 정보 섹션에 최근 달 기록이 없으므로, 도구가 그 달도 직접 돌려주게 합니다.
        if (materials.userDataSummary() != null) {
            context.put(UserDataTools.SUMMARY_PROVIDED, true);
        }
        return context;
    }
}
