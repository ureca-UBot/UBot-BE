package com.ubot.ai.service;

import com.ubot.ai.dto.AiAnswer;
import com.ubot.ai.dto.AnswerMaterials;
import com.ubot.ai.dto.ContextSection;
import com.ubot.ai.dto.ConversationTurn;
import com.ubot.ai.dto.OutputGuardResult;
import com.ubot.ai.dto.ProductDataCards;
import com.ubot.ai.dto.StoreMapResult;
import com.ubot.ai.dto.UserDataCards;
import com.ubot.ai.tool.AiToolRegistry;
import com.ubot.ai.tool.product.ProductDataCardSelector;
import com.ubot.ai.tool.product.ProductDataRecorder;
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
        // 가입 정보 도구가 조회한 사용량·청구서도 같은 이유로 요청마다 따로 보관합니다.
        UserDataRecorder userDataRecorder = new UserDataRecorder();
        // 상품 조회 도구가 돌려준 상품도 출력 검사 근거와 화면용 카드로 쓰려고 요청마다 따로 보관합니다.
        ProductDataRecorder productDataRecorder = new ProductDataRecorder();
        List<ToolCallback> callbacks = toolRegistry.resolve(materials.tools());
        if (!callbacks.isEmpty()) {
            request = request.withTools(callbacks,
                    toToolContext(materials, storeRecorder, userDataRecorder, productDataRecorder));
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
            // 상품 가격·제공량도 프롬프트 자료가 아니라 도구 결과에만 있으므로 같은 방식으로 근거에 덧붙입니다.
            String productRecords = productDataRecorder.describe();
            if (!productRecords.isEmpty()) {
                inspected = inspected.withSection(new ContextSection(ProductDataRecorder.SECTION_TITLE, productRecords));
            }
            inspection = aiOutputGuardService.inspectAnswer(parsed.answer(), parsed.evidenceIds(), inspected, storeMap);
            // 내부 결과를 사용해도 기존 전체 차단 오류와 자동 재시도 금지 정책은 유지합니다.
            if (inspection.rejected()) throw new LlmException(LlmErrorCode.LLM_OUTPUT_REJECTED);
            answer = inspection.answer();
            status = parsed.status();
        }
        // 가입 정보 카드는 도구로 조회한 기록과 미리 넣은 요약 중, 답변에 쓰인 기록만 합칩니다.
        // 도구로 조회만 하고 답변에 쓰지 않은 달은 붙이지 않습니다.
        // 모델이 답을 보류했으면 조회한 기록이 있어도 카드를 붙이지 않습니다. 미응답 판단에 쓰도록 status는 그대로 둡니다.
        // 상품 카드도 도구가 돌려준 상품 중 답변에 이름이 나온 것만 붙입니다.
        ProductDataCards productData = "ABSTAIN".equals(status) ? null
                : ProductDataCardSelector.select(answer, productDataRecorder.cards());
        // 상품 카드가 나가는 "요금제 종류 알려줘" 같은 답변에는 질문의 "요금제"만으로 내 요금제 카드를 붙이지 않습니다.
        UserDataCards userData = "ABSTAIN".equals(status) ? null
                : UserDataCards.merge(
                        UserDataCardSelector.selectRecorded(materials.question(), answer, userDataRecorder.cards()),
                        UserDataCardSelector.select(materials.question(), answer, materials.userDataSummary(),
                                productData == null));
        return new AiAnswer(answer, storeMap, storeRecorder.isLocationRequired(), status, inspection, userData,
                productData);
    }

    private Map<String, Object> toToolContext(AnswerMaterials materials, StoreSearchRecorder storeRecorder,
            UserDataRecorder userDataRecorder, ProductDataRecorder productDataRecorder) {
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
        context.put(ProductDataRecorder.CONTEXT_KEY, productDataRecorder);
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
