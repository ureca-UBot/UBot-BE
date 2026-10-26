package com.ubot.ai.service;

import com.ubot.ai.dto.AiAnswer;
import com.ubot.ai.dto.AnswerMaterials;
import com.ubot.ai.dto.ContextSection;
import com.ubot.ai.dto.ConversationTurn;
import com.ubot.ai.dto.LookupFailure;
import com.ubot.ai.dto.OutputGuardResult;
import com.ubot.ai.dto.ProductDataCards;
import com.ubot.ai.dto.StoreMapResult;
import com.ubot.ai.dto.SubQuestionFaqs;
import com.ubot.ai.dto.UserDataCards;
import com.ubot.ai.tool.AiToolRegistry;
import com.ubot.ai.tool.product.ProductDataCardSelector;
import com.ubot.ai.tool.product.ProductDataRecorder;
import com.ubot.ai.tool.product.ProductListAnswer;
import com.ubot.ai.tool.product.ProductType;
import com.ubot.ai.tool.StoreSearchRecorder;
import com.ubot.ai.tool.StoreTools;
import com.ubot.ai.tool.UserDataCardSelector;
import com.ubot.ai.tool.UserDataRecorder;
import com.ubot.ai.tool.UserDataTools;
import com.ubot.chat.context.StoreIntentHandler;
import com.ubot.chat.context.UserIntentHandler;
import com.ubot.faq.dto.response.FaqSearchResponseDto;
import com.ubot.faq.enums.Intent;
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
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
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
    private final SubQuestionLabels subQuestionLabels;

    public AiAnswer generateAnswer(AnswerMaterials materials) {
        // ChatAnswerProcessor가 intent별로 모은 자료를 받습니다. DB를 다시 조회하지 않습니다.
    	
    	// LLM에게 줄 자료(FAQ·추가 자료·도구)가 하나도 없으면 호출하지 않습니다. storeMap은 화면용이라 자료로 세지 않습니다.
        if (materials.faqs().isEmpty() && materials.sections().isEmpty() && materials.tools().isEmpty()) {
            throw new PromptException(PromptErrorCode.PROMPT_INPUT_MISSING);
        }
        // 도구가 있는 요청도 하위 질문마다 칸(answer → status)을 받습니다. 다만 도구가 있으면 출력 스키마가 강제되지 않아
        // 칸 수가 하위 질문 수와 다를 수 있으므로, 그때는 칸을 하나로 합쳐 질문 하나로 봅니다(answerBySlots).
        List<ToolCallback> callbacks = toolRegistry.resolve(materials.tools());
        boolean toolPath = !callbacks.isEmpty();

        // 승지님이 작성한 프롬프트에 질문·FAQ·추가 자료를 넣어 메시지를 구성합니다.
        // 이전 대화는 이번 질문이 이전 대화를 써야 할 때만 들어 있습니다(SubQuestionPlanner의 멀티턴 판정).
        LlmRequestDto request = promptService.createSlotPrompt(materials.question(), materials.faqs(),
                materials.subQuestions(), materials.sections(), materials.history());

        // 도구가 조회한 매장 목록은 LLM 답변 문장과 별도로 화면에 전달하기 위해 요청마다 새로 보관합니다.
        StoreSearchRecorder storeRecorder = new StoreSearchRecorder();
        // 가입 정보 도구가 조회한 사용량·청구서도 같은 이유로 요청마다 따로 보관합니다.
        UserDataRecorder userDataRecorder = new UserDataRecorder();
        // 상품 조회 도구가 돌려준 상품도 출력 검사 근거와 화면용 카드로 쓰려고 요청마다 따로 보관합니다.
        ProductDataRecorder productDataRecorder = new ProductDataRecorder(materials.question(),
                materials.history().stream().map(ConversationTurn::question).filter(StringUtils::hasText).toList());
        if (toolPath) {
            request = request.withTools(callbacks,
                    toToolContext(materials, storeRecorder, userDataRecorder, productDataRecorder));
        }

        // 프롬프트가 준비됐을 때만 LLM을 호출하고, 생성된 답변을 채팅 쪽으로 반환합니다.
        // 빈 응답 판단은 ChatAnswerProcessor가 합니다.
        LlmResponseDto response;
        try {
            response = llmService.generateAnswer(request);
        } catch (LlmException exception) {
            // 목록 답변은 LLM 답변을 쓰지 않으므로, 도구 조회 뒤 LLM의 마지막 응답만 깨졌으면 목록으로 답합니다.
            if (exception.getErrorCode() != LlmErrorCode.LLM_RESPONSE_INVALID || !listingAnswerable(materials)
                    || productDataRecorder.listing().isEmpty()) {
                throw exception;
            }
            log.warn("LLM 응답이 올바르지 않지만 상품 목록 요청이라 목록 답변으로 답합니다");
            response = null;
        }
        // 기존 파서와 출력 가드로 한 번 검사하고, 후보 선별에는 상태가 아닌 실제 검사 결과를 전달합니다.
        StoreMapResult storeMap = storeRecorder.result().orElse(materials.storeMap());
        String answer = null;
        String status = null;
        OutputGuardResult inspection = null;
        List<AiAnswer.SlotOutcome> slotOutcomes = null;
        boolean responded = response != null && StringUtils.hasText(response.answer());
        // 목록 요청이면 LLM 답변(칸 형식 포함) 대신 코드가 만든 목록 답변을 씁니다(ProductListAnswer 참고).
        // LLM 답변은 읽지 않으므로 LLM 응답이 비었거나 깨졌어도 목록으로 답합니다.
        Optional<ProductListAnswer> listing = listingAnswerable(materials) ? productDataRecorder.listing() : Optional.empty();
        if (responded || listing.isPresent()) {
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
            if (listing.isPresent()) {
                log.info("상품 목록 요청이라 목록 답변으로 바꿨습니다: LLM응답={}, 형식={}, 카드수={}",
                        responded ? "있음" : "없음", listing.get().getClass().getSimpleName(),
                        listing.get().products().size());
                inspection = aiOutputGuardService.inspectAnswer(listing.get().answer(), List.of(), inspected, storeMap);
                // 내부 결과를 사용해도 기존 전체 차단 오류와 자동 재시도 금지 정책은 유지합니다.
                if (inspection.rejected()) throw new LlmException(LlmErrorCode.LLM_OUTPUT_REJECTED);
                answer = inspection.answer();
                status = SubQuestionStatuses.ANSWER;
            } else {
                FaqAnswer parsed = FaqAnswerFormat.parse(response.answer());
                log.info("LLM 답변을 파싱했습니다: status={}, evidenceIds={}", parsed.status(), parsed.evidenceIds());
                if (parsed.slots() != null) {
                    SlotResult slotResult = answerBySlots(parsed, inspected, storeMap, toolPath,
                            lookupFailures(materials, storeRecorder, userDataRecorder));
                    answer = slotResult.answer();
                    status = slotResult.status();
                    inspection = slotResult.inspection();
                    slotOutcomes = slotResult.outcomes();
                } else {
                    // 칸 형식이 아닌 출력(도구 경로에서 일반 문장으로 답한 경우 등)은 기존처럼 본문 전체를 검사해 씁니다.
                    inspection = aiOutputGuardService.inspectAnswer(parsed.answer(), parsed.evidenceIds(), inspected,
                            storeMap);
                    // 내부 결과를 사용해도 기존 전체 차단 오류와 자동 재시도 금지 정책은 유지합니다.
                    if (inspection.rejected()) throw new LlmException(LlmErrorCode.LLM_OUTPUT_REJECTED);
                    answer = inspection.answer();
                    status = parsed.status();
                }
            }
        }
        // 가입 정보 카드는 도구로 조회한 기록과 미리 넣은 요약 중, 답변에 쓰인 기록만 합칩니다.
        // 도구로 조회만 하고 답변에 쓰지 않은 달은 붙이지 않습니다.
        // 모델이 답을 보류했으면 조회한 기록이 있어도 카드를 붙이지 않습니다. 미응답 판단에 쓰도록 status는 그대로 둡니다.
        // 상품 카드도 도구가 돌려준 상품 중 답변에 이름이 나온 것만 붙입니다. 목록 답변이면 목록의 상품 전부입니다.
        ProductDataCards productData = listing.isPresent()
                ? (listing.get().products().isEmpty() ? null : new ProductDataCards(listing.get().products()))
                : "ABSTAIN".equals(status) ? null
                : ProductDataCardSelector.select(answer, productDataRecorder.cards());
        // 상품 카드가 나가는 "요금제 종류 알려줘" 같은 답변에는 질문의 "요금제"만으로 내 요금제 카드를 붙이지 않습니다.
        UserDataCards userData = "ABSTAIN".equals(status) ? null
                : UserDataCards.merge(
                        UserDataCardSelector.selectRecorded(materials.question(), answer, userDataRecorder.cards()),
                        UserDataCardSelector.select(materials.question(), answer, materials.userDataSummary(),
                                productData == null));
        return new AiAnswer(answer, storeMap, storeRecorder.isLocationRequired(), status, inspection, userData,
                productData, slotOutcomes);
    }

    /**
     * 목록 답변으로 대신할 수 있는 질문인지입니다. 하위 질문이 하나이고 상품 도구만 붙었을 때만입니다.
     * "프리미엄 요금제 종류랑 해지 방법"처럼 다른 질문이 섞였거나 매장·가입 정보 도구가 함께 붙었으면 LLM 답변을 씁니다.
     */
    private static boolean listingAnswerable(AnswerMaterials materials) {
        return materials.subQuestions().size() <= 1 && materials.tools().stream().allMatch(ProductType::isProductTool);
    }

    /**
     * 칸 형식 출력을 하위 질문마다 처리합니다. 답한 칸만 출력 가드로 검사하고, 답하지 못한 하위 질문의 고지는 코드가 붙입니다.
     * <ul>
     *   <li>가드는 칸마다 그 하위 질문(원문 조각)을 질문으로 삼아 검사합니다. 다른 하위 질문의 범위를 이 칸의 누락으로 보지 않게 합니다.</li>
     *   <li>가드가 문장 단위로 지웠거나 칸을 거부하면 그 칸은 ABSTAIN으로 바꾸고 버립니다. 표현만 정리했으면 정리한 칸을 씁니다.</li>
     *   <li>정책 위반으로 칸을 거부하면 기존과 같이 답변 전체를 거부합니다(자동 재시도 없음).</li>
     *   <li>가입 정보·매장 조회가 실패해 그 조회가 필요한 하위 질문을 답하지 못했으면 일반 고지 대신 사유별 고지를 씁니다.</li>
     *   <li>모든 칸이 답하지 못한 것이면 본문 없이 고지만 냅니다. 같은 기준으로 모두 답하지 못했으면 전체 고지 하나입니다.</li>
     * </ul>
     * 전체 status는 가드를 거친 하위 status를 조합한 값입니다. 알 수 없는 status가 있으면 응답 오류로 처리합니다.
     * 칸 수가 하위 질문 수와 다르면, 도구 경로(스키마가 강제되지 않음)는 칸을 하나로 합쳐 질문 하나로 보고
     * 그 밖의 경로(스키마로 칸 수를 강제함)는 응답 오류로 처리합니다.
     */
    private SlotResult answerBySlots(FaqAnswer parsed, AnswerMaterials materials, StoreMapResult storeMap,
            boolean toolPath, Map<LookupFailure.Target, LookupFailure> failures) {
        List<SlotAnswer> slots = parsed.slots();
        int expected = PromptService.slotCount(materials.subQuestions());
        if (slots.isEmpty() || slots.stream().anyMatch(slot -> slot.status() == null)
                || (slots.size() != expected && !toolPath)) {
            log.warn("LLM 칸 형식 답변이 하위 질문과 맞지 않습니다: 칸수={}, 하위질문칸수={}, statuses={}",
                    slots.size(), expected, slots.stream().map(SlotAnswer::status).toList());
            throw new LlmException(LlmErrorCode.LLM_RESPONSE_INVALID);
        }
        // ANSWER인데 answer가 비었으면 그 하위 질문은 사용자에게 아무 답도 나가지 않으므로 ABSTAIN으로 봅니다.
        List<String> slotStatuses = slots.stream()
                .map(slot -> SubQuestionStatuses.ANSWER.equals(slot.status()) && !StringUtils.hasText(slot.answer())
                        ? SubQuestionStatuses.ABSTAIN : slot.status())
                .toList();
        List<String> answers;
        List<String> statuses;
        if (slots.size() == expected) {
            answers = slots.stream().map(SlotAnswer::answer).toList();
            statuses = new ArrayList<>(slotStatuses);
        } else {
            // 도구 경로에서 칸 수가 맞지 않으면 어느 칸이 어느 하위 질문인지 알 수 없으므로, 답한 칸의 answer를 이어 질문 하나로 봅니다.
            log.info("도구 경로의 칸 수가 하위 질문 수와 달라 칸을 하나로 합칩니다: 칸수={}, 하위질문칸수={}, statuses={}",
                    slots.size(), expected, slotStatuses);
            answers = List.of(IntStream.range(0, slots.size())
                    .filter(index -> SubQuestionStatuses.ANSWER.equals(slotStatuses.get(index)))
                    .mapToObj(index -> slots.get(index).answer())
                    .collect(Collectors.joining("\n\n")));
            statuses = new ArrayList<>(List.of(SubQuestionStatuses.combine(slotStatuses)));
            expected = 1;
        }
        log.info("LLM 칸 형식 답변을 파싱했습니다: statuses={}, evidenceIds={}", statuses, parsed.evidenceIds());

        List<String> kept = new ArrayList<>();
        Set<OutputGuardResult.Reason> reasons = EnumSet.noneOf(OutputGuardResult.Reason.class);
        boolean inspected = false;
        boolean[] changedByGuard = new boolean[statuses.size()];
        for (int index = 0; index < statuses.size(); index++) {
            // 답한 칸만 검사합니다. 칸을 합친 경우 일부만 답했으면(PARTIAL) 답한 내용이 있으므로 함께 검사합니다.
            String slotStatus = statuses.get(index);
            if (!SubQuestionStatuses.ANSWER.equals(slotStatus) && !SubQuestionStatuses.PARTIAL.equals(slotStatus)) {
                continue;
            }
            AnswerMaterials scoped = expected > 1 ? scopedTo(materials, materials.subQuestions().get(index)) : materials;
            OutputGuardResult result = aiOutputGuardService.inspectAnswer(
                    answers.get(index), parsed.evidenceIds(), scoped, storeMap);
            inspected = true;
            reasons.addAll(result.reasons());
            if (result.rejected() && result.reasons().contains(OutputGuardResult.Reason.POLICY_VIOLATION)) {
                throw new LlmException(LlmErrorCode.LLM_OUTPUT_REJECTED);
            }
            if (result.rejected() || result.contentRemoved()) {
                log.info("출력 가드가 칸의 내용을 지워 답하지 못한 것으로 바꿉니다: 칸={}, 거부={}, 사유={}",
                        index + 1, result.rejected(), result.reasons());
                statuses.set(index, SubQuestionStatuses.ABSTAIN);
                changedByGuard[index] = true;
                continue;
            }
            kept.add(result.answer());
        }
        String body = String.join("\n\n", kept);
        List<SubQuestionStatuses.NoticeKey> keys = SubQuestionStatuses.noticeKeys(statuses,
                lookupTargets(materials, expected), failures);
        String notice = SubQuestionStatuses.notice(keys, noticeLabels(keys, materials.subQuestions()));
        OutputGuardResult inspection = inspected ? new OutputGuardResult(body, false, reasons) : null;
        List<AiAnswer.SlotOutcome> outcomes = new ArrayList<>();
        for (int index = 0; index < statuses.size(); index++) {
            outcomes.add(new AiAnswer.SlotOutcome(statuses.get(index), changedByGuard[index],
                    keys.get(index).failure() != null));
        }
        return new SlotResult(SubQuestionStatuses.compose(body, notice), SubQuestionStatuses.combine(statuses), inspection,
                List.copyOf(outcomes));
    }

    /**
     * 하위 고지에 넣을 원문 조각입니다. 하위 고지를 낼 때(일부만 답하지 못했을 때)만, 답하지 못한 하위 질문의 조각만 다듬습니다.
     * 칸이 하나이거나 전체 고지 하나로 끝나면 조각이 필요 없으므로 형태소 분석을 하지 않습니다.
     */
    private List<String> noticeLabels(List<SubQuestionStatuses.NoticeKey> keys, List<SubQuestionFaqs> subQuestions) {
        if (!SubQuestionStatuses.needsPartNotice(keys)) {
            return List.of();
        }
        List<String> labels = new ArrayList<>();
        for (int index = 0; index < keys.size(); index++) {
            labels.add(keys.get(index).answered() ? null : subQuestionLabels.label(subQuestions.get(index).userText()));
        }
        return labels;
    }

    /**
     * 가입 정보·매장을 조회하지 못한 사유입니다. 도구가 남긴 사유를 먼저 쓰고, 없으면 핸들러가 넣은 섹션으로 판단합니다.
     * 섹션: 비로그인·없는 사용자·요약 조회 실패(사용자 정보), 내 위치 기준 매장 조회 실패(현재 위치 근처 매장).
     */
    private static Map<LookupFailure.Target, LookupFailure> lookupFailures(AnswerMaterials materials,
            StoreSearchRecorder storeRecorder, UserDataRecorder userDataRecorder) {
        Map<LookupFailure.Target, LookupFailure> failures = new EnumMap<>(LookupFailure.Target.class);
        userDataRecorder.failure().ifPresent(failure -> failures.put(LookupFailure.Target.USER_DATA, failure));
        storeRecorder.failure().ifPresent(failure -> failures.put(LookupFailure.Target.STORE_DATA, failure));
        for (ContextSection section : materials.sections()) {
            LookupFailure.Kind kind = null;
            if (UserIntentHandler.SECTION_TITLE.equals(section.title())) {
                if (UserIntentHandler.LOGIN_REQUIRED_CONTENT.equals(section.content())) kind = LookupFailure.Kind.LOGIN_REQUIRED;
                if (UserIntentHandler.NOT_FOUND_CONTENT.equals(section.content())) kind = LookupFailure.Kind.USER_NOT_FOUND;
                if (UserIntentHandler.LOOKUP_FAILED_CONTENT.equals(section.content())) kind = LookupFailure.Kind.USER_LOOKUP_FAILED;
            } else if (StoreIntentHandler.SECTION_TITLE.equals(section.title())
                    && StoreIntentHandler.LOOKUP_FAILED_CONTENT.equals(section.content())) {
                kind = LookupFailure.Kind.STORE_LOOKUP_FAILED;
            }
            if (kind != null) {
                failures.putIfAbsent(kind.target(), LookupFailure.of(kind));
            }
        }
        return failures;
    }

    /**
     * 칸마다 어떤 조회가 필요한 하위 질문인지입니다(근거 FAQ의 USER_DATA·STORE_DATA).
     * 칸이 하나면 하위 질문 전체에서 하나라도 해당하는지입니다.
     */
    private static List<List<LookupFailure.Target>> lookupTargets(AnswerMaterials materials, int slots) {
        if (slots == 1) {
            List<FaqSearchResponseDto> all = new ArrayList<>(materials.faqs());
            materials.subQuestions().forEach(subQuestion -> all.addAll(subQuestion.faqs()));
            return List.of(targetsOf(all));
        }
        return materials.subQuestions().stream().map(subQuestion -> targetsOf(subQuestion.faqs())).toList();
    }

    private static List<LookupFailure.Target> targetsOf(List<FaqSearchResponseDto> faqs) {
        List<LookupFailure.Target> targets = new ArrayList<>();
        if (faqs.stream().anyMatch(faq -> faq.intent() == Intent.USER_DATA)) targets.add(LookupFailure.Target.USER_DATA);
        if (faqs.stream().anyMatch(faq -> faq.intent() == Intent.STORE_DATA)) targets.add(LookupFailure.Target.STORE_DATA);
        return targets;
    }

    /**
     * 칸 하나를 검사할 자료입니다. 질문은 그 하위 질문의 원문 조각(없으면 검색어)이고, FAQ·추가 자료는 모델에 준 그대로 둡니다.
     * 모델은 모든 자료를 보고 답하므로, 근거 대조에는 전체 자료를 씁니다.
     */
    private static AnswerMaterials scopedTo(AnswerMaterials materials, SubQuestionFaqs subQuestion) {
        String question = StringUtils.hasText(subQuestion.userText()) ? subQuestion.userText() : subQuestion.searchQuery();
        return new AnswerMaterials(question, materials.faqs(), materials.sections(), materials.tools(), materials.storeMap(),
                List.of(subQuestion), materials.history(), materials.userId(), materials.userDataSummary(),
                materials.toolRecords());
    }

    private record SlotResult(String answer, String status, OutputGuardResult inspection,
            List<AiAnswer.SlotOutcome> outcomes) {
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
