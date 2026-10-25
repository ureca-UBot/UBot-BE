package com.ubot.ai.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.ubot.ai.config.AiOutputGuardProperties;
import com.ubot.ai.dto.AiAnswer;
import com.ubot.ai.dto.AnswerMaterials;
import com.ubot.ai.dto.ContextSection;
import com.ubot.ai.dto.ConversationTurn;
import com.ubot.ai.dto.OutputGuardResult;
import com.ubot.ai.dto.StoreMapResult;
import com.ubot.ai.dto.SubQuestionFaqs;
import com.ubot.ai.tool.AiToolRegistry;
import com.ubot.faq.dto.response.FaqSearchResponseDto;
import com.ubot.faq.enums.Intent;
import com.ubot.llm.dto.request.LlmRequestDto;
import com.ubot.llm.dto.response.LlmResponseDto;
import com.ubot.llm.exception.LlmErrorCode;
import com.ubot.llm.exception.LlmException;
import com.ubot.llm.service.LlmService;
import com.ubot.prompt.service.PromptService;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.json.JsonMapper;

/** 기존 칸 처리와 출력 가드 연결 경계를 검증합니다. 모델·검색·DB는 호출하지 않습니다. */
class AiGuardSlotMergeConnectionTest {

    private static final String FIRST = "요금은 앱에서 확인할 수 있습니다.";
    private static final String SECOND = "매장 영업시간은 홈페이지에서 확인할 수 있습니다.";
    private final PromptService prompts = mock(PromptService.class);
    private final LlmService generation = mock(LlmService.class);
    private final AiToolRegistry tools = mock(AiToolRegistry.class);
    private final AiOutputGuardService code = mock(AiOutputGuardService.class);
    private final AiLlmOutputGuardService output = mock(AiLlmOutputGuardService.class);
    private final AiOutputGuardProperties properties = new AiOutputGuardProperties();
    private final AiService service = new AiService(prompts, generation, tools, code,
            SubQuestionLabels.withoutMorphology(), output, properties);
    private final AnswerMaterials materials = materials();

    @BeforeEach
    void setUp() {
        when(tools.resolve(any())).thenReturn(List.of());
        when(prompts.createSlotPrompt(any(), any(), any(), any(), any()))
                .thenReturn(new LlmRequestDto(List.of()));
        when(code.inspectAnswer(any(), any(), any(), any()))
                .thenAnswer(call -> new OutputGuardResult(call.getArgument(0), false, Set.of()));
    }

    @Test
    void inspectsEachSlotWithCodeThenChecksTheWholeAnswerWithAiOnce() {
        prepare("ANSWER", "ANSWER");

        AiAnswer answer = service.generateAnswer(materials);

        assertThat(answer.answer()).isEqualTo(FIRST + "\n\n" + SECOND);
        assertThat(answer.status()).isEqualTo("ANSWER");
        assertThat(answer.slotOutcomes()).containsExactly(new AiAnswer.SlotOutcome("ANSWER", false),
                new AiAnswer.SlotOutcome("ANSWER", false));
        var order = inOrder(code, output);
        order.verify(code).inspectAnswer(eq(FIRST), any(), any(), any());
        order.verify(code).inspectAnswer(eq(SECOND), any(), any(), any());
        order.verify(output).validate(eq(answer.answer()), any(), any(), any());
        var scoped = ArgumentCaptor.forClass(AnswerMaterials.class);
        verify(code, times(2)).inspectAnswer(any(), any(), scoped.capture(), any());
        assertThat(scoped.getAllValues()).extracting(AnswerMaterials::question)
                .containsExactly("요금은 어디서 확인하고", "매장 영업시간은 어디서 봐요?");
        assertThat(scoped.getAllValues()).allSatisfy(value -> assertThat(value.faqs()).isEqualTo(materials.faqs()));
        verify(output, times(1)).validate(eq(answer.answer()), eq(List.of("1", "2")), eq(materials), any());
        verify(prompts).createSlotPrompt(materials.question(), materials.faqs(), materials.subQuestions(),
                materials.sections(), materials.history());
        verify(prompts, never()).createPrompt(any(), any(), any(), any(), any());
        verify(generation, times(1)).generateAnswer(any());
    }

    @ParameterizedTest
    @CsvSource({"1,-1", "2,-1", "3,-1", "3,1"})
    void oneGuardRequestContainsTheKeptAnswersAndOriginalContextRegardlessOfSlotCount(int count, int rejectedIndex) {
        var model = mock(AiGuardModelService.class);
        var words = mock(com.ubot.forbiddenword.service.ForbiddenWordFilterService.class);
        var guard = new AiLlmOutputGuardService(model, new com.ubot.prompt.service.AiGuardPromptService(words));
        var ai = new AiService(prompts, generation, tools, code, SubQuestionLabels.withoutMorphology(), guard, properties);
        var faqs = List.of(
                new FaqSearchResponseDto(1L, "요금 확인", FIRST, 0.95, Intent.GENERAL),
                new FaqSearchResponseDto(2L, "영업시간 확인", SECOND, 0.95, Intent.GENERAL),
                new FaqSearchResponseDto(3L, "요금제 변경", "요금제는 앱에서 변경할 수 있습니다.", 0.95, Intent.GENERAL));
        var subQuestions = faqs.subList(0, count).stream()
                .map(faq -> new SubQuestionFaqs(faq.question(), List.of(faq), faq.question())).toList();
        var stores = new StoreMapResult(new com.ubot.ai.dto.Location(37.5, 127.0), "기준 위치", 3.0, List.of());
        var context = AnswerMaterials.builder("요금과 매장 영업시간, 요금제 변경을 알려 주세요.").addFaqs(faqs)
                .addSection(new ContextSection("추가 조건", "이용 조건은 앞 문장과 이어집니다."))
                .subQuestions(subQuestions).storeMap(stores).build()
                .withHistory(List.of(new ConversationTurn("앞선 질문", "앞선 안내")));
        var json = JsonMapper.builder().build();
        var slots = faqs.subList(0, count).stream().map(faq -> Map.of("answer", faq.answer(), "status", "ANSWER")).toList();
        when(generation.generateAnswer(any())).thenReturn(new LlmResponseDto(
                json.writeValueAsString(Map.of("answers", slots, "evidence_ids", List.of("1", "2", "3")))));
        when(model.generate(any())).thenReturn(new LlmResponseDto(
                "{\"findings\":[]}"));
        if (rejectedIndex >= 0) {
            when(code.inspectAnswer(eq(faqs.get(rejectedIndex).answer()), any(), any(), any()))
                    .thenReturn(new OutputGuardResult(null, true, Set.of(OutputGuardResult.Reason.ANSWER_ERROR)));
        }

        AiAnswer answer = ai.generateAnswer(context);

        var request = ArgumentCaptor.forClass(LlmRequestDto.class);
        verify(model, times(1)).generate(request.capture());
        var data = json.readTree(request.getValue().messages().getLast().content());
        assertThat(data.get("candidate_answer").asString()).isEqualTo(answer.answer());
        String kept = java.util.stream.IntStream.range(0, count).filter(i -> i != rejectedIndex)
                .mapToObj(i -> faqs.get(i).answer()).collect(java.util.stream.Collectors.joining("\n\n"));
        if (rejectedIndex < 0) {
            assertThat(answer.answer()).isEqualTo(kept);
        } else {
            assertThat(answer.answer()).startsWith(kept).doesNotContain(faqs.get(rejectedIndex).answer())
                    .contains("확인해 드리기 어렵습니다.");
            assertThat(answer.slotOutcomes().get(rejectedIndex)).isEqualTo(new AiAnswer.SlotOutcome("ABSTAIN", true));
        }
        assertThat(data.get("question").asString()).isEqualTo(context.question());
        assertThat(data.get("provided_faqs").size()).isEqualTo(3);
        assertThat(data.get("sub_questions").size()).isEqualTo(count);
        assertThat(data.get("additional_evidence").get(0).get("content").asString()).isEqualTo("이용 조건은 앞 문장과 이어집니다.");
        assertThat(data.get("history").get(0).get("answer").asString()).isEqualTo("앞선 안내");
        assertThat(data.get("store_results").isObject()).isTrue();
        assertThat(data.get("execution_results").get(0).get("operation").asString()).isEqualTo("STORE_SEARCH");
        var order = inOrder(code, model);
        for (var faq : faqs.subList(0, count)) order.verify(code).inspectAnswer(eq(faq.answer()), any(), any(), any());
        order.verify(model).generate(any());
        verify(generation, times(1)).generateAnswer(any());
    }

    @ParameterizedTest
    @EnumSource(value = OutputGuardResult.Reason.class, names = {"POLICY_VIOLATION"})
    void aPolicyViolationStopsTheWholeAnswerBeforeItsAiCheck(OutputGuardResult.Reason reason) {
        prepare("ANSWER", "ANSWER");
        when(code.inspectAnswer(eq(FIRST), any(), any(), any()))
                .thenReturn(new OutputGuardResult(null, true, Set.of(reason)));

        assertRejected(LlmErrorCode.LLM_OUTPUT_REJECTED);

        verifyNoInteractions(output);
        verify(code, times(1)).inspectAnswer(any(), any(), any(), any());
        verify(generation, times(1)).generateAnswer(any());
    }

    @ParameterizedTest
    @EnumSource(value = OutputGuardResult.Reason.class,
            names = {"ANSWER_ERROR", "FORMAT_ERROR", "FALSE_EXECUTION", "UNCLASSIFIED"})
    void aRejectedFirstSlotBecomesAbstainWhileTheNormalSecondAnswerIsKept(OutputGuardResult.Reason reason) {
        prepare("ANSWER", "ANSWER");
        when(code.inspectAnswer(eq(FIRST), any(), any(), any()))
                .thenReturn(new OutputGuardResult(null, true, Set.of(reason)));

        AiAnswer answer = service.generateAnswer(materials);

        assertThat(answer.status()).isEqualTo("PARTIAL");
        assertThat(answer.answer()).startsWith(SECOND).doesNotContain(FIRST);
        assertThat(answer.slotOutcomes()).containsExactly(new AiAnswer.SlotOutcome("ABSTAIN", true),
                new AiAnswer.SlotOutcome("ANSWER", false));
        verify(output, never()).validate(eq(FIRST), any(), any(), any());
        verify(output, times(1)).validate(eq(answer.answer()), any(), eq(materials), any());
        verify(generation, times(1)).generateAnswer(any());
    }

    @Test
    void aRejectedSecondSlotBecomesAbstainWithoutDiscardingTheNormalFirstAnswerOrRetrying() {
        prepare("ANSWER", "ANSWER");
        when(code.inspectAnswer(eq(SECOND), any(), any(), any()))
                .thenReturn(new OutputGuardResult(null, true, Set.of(OutputGuardResult.Reason.ANSWER_ERROR)));

        AiAnswer answer = service.generateAnswer(materials);

        assertThat(answer.status()).isEqualTo("PARTIAL");
        assertThat(answer.answer()).startsWith(FIRST).doesNotContain(SECOND);
        assertThat(answer.slotOutcomes()).containsExactly(new AiAnswer.SlotOutcome("ANSWER", false),
                new AiAnswer.SlotOutcome("ABSTAIN", true));

        verify(output, times(1)).validate(eq(answer.answer()), any(), eq(materials), any());
        verify(output, never()).validate(eq(SECOND), any(), any(), any());
        verify(generation, times(1)).generateAnswer(any());
    }

    @Test
    void aPartlyRemovedSlotIsDiscardedAsAWholeWhileTheOtherAnswerIsKept() {
        prepare("ANSWER", "ANSWER");
        when(code.inspectAnswer(eq(FIRST), any(), any(), any()))
                .thenReturn(new OutputGuardResult("남은 안내", false,
                        Set.of(OutputGuardResult.Reason.ANSWER_ERROR), true));

        AiAnswer answer = service.generateAnswer(materials);

        assertThat(answer.status()).isEqualTo("PARTIAL");
        assertThat(answer.answer()).startsWith(SECOND).doesNotContain(FIRST, "남은 안내");
        assertThat(answer.slotOutcomes()).containsExactly(new AiAnswer.SlotOutcome("ABSTAIN", true),
                new AiAnswer.SlotOutcome("ANSWER", false));
        verify(output, never()).validate(eq("남은 안내"), any(), any(), any());
        verify(output, times(1)).validate(eq(answer.answer()), any(), eq(materials), any());
        verify(generation, times(1)).generateAnswer(any());
    }

    @Test
    void removedPolicyViolatingContentStillRejectsTheWholeAnswer() {
        prepare("ANSWER", "ANSWER");
        when(code.inspectAnswer(eq(FIRST), any(), any(), any()))
                .thenReturn(new OutputGuardResult("남은 안내", false,
                        Set.of(OutputGuardResult.Reason.POLICY_VIOLATION), true));

        assertRejected(LlmErrorCode.LLM_OUTPUT_REJECTED);

        verifyNoInteractions(output);
        verify(generation, times(1)).generateAnswer(any());
    }

    @Test
    void aPolicyViolationInTheSecondSlotStillDiscardsTheNormalFirstAnswer() {
        prepare("ANSWER", "ANSWER");
        when(code.inspectAnswer(eq(SECOND), any(), any(), any()))
                .thenReturn(new OutputGuardResult(null, true, Set.of(OutputGuardResult.Reason.POLICY_VIOLATION)));

        assertRejected(LlmErrorCode.LLM_OUTPUT_REJECTED);

        verifyNoInteractions(output);
        verify(generation, times(1)).generateAnswer(any());
    }

    @Test
    void allRejectedNonPolicySlotsReturnOnlyTheExistingAbstentionNotice() {
        prepare("ANSWER", "ANSWER");
        when(code.inspectAnswer(any(), any(), any(), any()))
                .thenReturn(new OutputGuardResult(null, true, Set.of(OutputGuardResult.Reason.ANSWER_ERROR)));

        AiAnswer answer = service.generateAnswer(materials);

        assertThat(answer.status()).isEqualTo("ABSTAIN");
        assertThat(answer.answer()).isEqualTo("문의하신 내용은 현재 안내 자료로는 확인해 드리기 어렵습니다.");
        assertThat(answer.slotOutcomes()).containsExactly(new AiAnswer.SlotOutcome("ABSTAIN", true),
                new AiAnswer.SlotOutcome("ABSTAIN", true));
        verifyNoInteractions(output);
        verify(generation, times(1)).generateAnswer(any());
    }

    @Test
    void aRemovedNonSlotAnswerIsNeverReturnedAsAFilteredSuccess() {
        when(generation.generateAnswer(any())).thenReturn(new LlmResponseDto(
                "{\"answer\":\"원래 답변\",\"status\":\"ANSWER\",\"evidence_ids\":[]}"));
        when(code.inspectAnswer(any(), any(), any(), any()))
                .thenReturn(new OutputGuardResult("남은 안내", false,
                        Set.of(OutputGuardResult.Reason.ANSWER_ERROR), true));

        assertRejected(LlmErrorCode.LLM_OUTPUT_REJECTED);

        verifyNoInteractions(output);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void aRejectedOrRemovedOfficialCacheAnswerNeverBecomesASuccess(boolean removed) {
        when(code.inspectAnswer(any(), any(), any(), any()))
                .thenReturn(new OutputGuardResult("남은 안내", !removed,
                        Set.of(OutputGuardResult.Reason.ANSWER_ERROR), removed));

        assertThatThrownBy(() -> service.validateCachedAnswer(materials, FIRST, "1"))
                .isInstanceOfSatisfying(LlmException.class,
                        error -> assertThat(error.getErrorCode()).isEqualTo(LlmErrorCode.LLM_OUTPUT_REJECTED));

        verifyNoInteractions(generation, output);
    }

    @ParameterizedTest
    @EnumSource(value = LlmErrorCode.class, names = {"LLM_OUTPUT_REJECTED", "LLM_GUARD_REVIEW_REQUIRED",
            "LLM_TIMEOUT", "LLM_RESPONSE_INVALID"})
    void aiBlockReviewAndFailuresPropagateWithoutAllowOrRetry(LlmErrorCode error) {
        prepare("ANSWER", "ANSWER");
        doThrow(new LlmException(error)).when(output).validate(eq(FIRST + "\n\n" + SECOND), any(), any(), any());

        assertRejected(error);

        verify(code, times(2)).inspectAnswer(any(), any(), any(), any());
        verify(output, times(1)).validate(any(), any(), any(), any());
        verify(generation, times(1)).generateAnswer(any());
    }

    @Test
    void theWholeSwitchSkipsBothGuardsAndPreservesSlotProcessing() {
        properties.setEnabled(false);
        prepare("ANSWER", "ANSWER");

        AiAnswer answer = service.generateAnswer(materials);

        assertThat(answer.answer()).isEqualTo(FIRST + "\n\n" + SECOND);
        assertThat(answer.status()).isEqualTo("ANSWER");
        assertThat(answer.outputGuardResult().reasons()).isEmpty();
        verifyNoInteractions(code, output);
    }

    @Test
    void theAiSwitchKeepsEachExistingCodeInspection() {
        properties.setLlmEnabled(false);
        prepare("ANSWER", "ANSWER");

        assertThat(service.generateAnswer(materials).answer()).isEqualTo(FIRST + "\n\n" + SECOND);

        verify(code, times(2)).inspectAnswer(any(), any(), any(), any());
        verifyNoInteractions(output);
    }

    @ParameterizedTest
    @ValueSource(strings = {"ABSTAIN", "CONFLICT"})
    void modelAbstentionAndConflictKeepExistingPartialNotices(String status) {
        prepare("ANSWER", status);

        AiAnswer answer = service.generateAnswer(materials);

        assertThat(answer.status()).isEqualTo("PARTIAL");
        assertThat(answer.answer()).startsWith(FIRST).doesNotContain(SECOND);
        assertThat(answer.slotOutcomes()).containsExactly(new AiAnswer.SlotOutcome("ANSWER", false),
                new AiAnswer.SlotOutcome(status, false));
        verify(code, times(1)).inspectAnswer(eq(FIRST), any(), any(), any());
        verify(output, times(1)).validate(eq(answer.answer()), any(), eq(materials), any());
    }

    @Test
    void allAbstainedSlotsKeepTheExistingNoticeWithoutGuardCalls() {
        prepare("ABSTAIN", "ABSTAIN");

        AiAnswer answer = service.generateAnswer(materials);

        assertThat(answer.status()).isEqualTo("ABSTAIN");
        assertThat(answer.outputGuardResult()).isNull();
        verifyNoInteractions(code, output);
    }

    private void assertRejected(LlmErrorCode code) {
        assertThatThrownBy(() -> service.generateAnswer(materials))
                .isInstanceOfSatisfying(LlmException.class,
                        error -> assertThat(error.getErrorCode()).isEqualTo(code));
    }

    private void prepare(String firstStatus, String secondStatus) {
        String json = "{\"answers\":[{\"answer\":\"" + FIRST + "\",\"status\":\"" + firstStatus
                + "\"},{\"answer\":\"" + SECOND + "\",\"status\":\"" + secondStatus
                + "\"}],\"evidence_ids\":[\"1\",\"2\"]}";
        when(generation.generateAnswer(any())).thenReturn(new LlmResponseDto(json));
    }

    private static AnswerMaterials materials() {
        var faqs = List.of(new FaqSearchResponseDto(1L, "요금 확인", FIRST, 0.95, Intent.GENERAL),
                new FaqSearchResponseDto(2L, "영업시간 확인", SECOND, 0.95, Intent.GENERAL));
        return AnswerMaterials.builder("요금은 어디서 확인하고 매장 영업시간은 어디서 봐요?")
                .addFaqs(faqs).subQuestions(List.of(
                        new SubQuestionFaqs("요금 확인", List.of(faqs.getFirst()), "요금은 어디서 확인하고"),
                        new SubQuestionFaqs("영업시간 확인", List.of(faqs.getLast()), "매장 영업시간은 어디서 봐요?")))
                .build();
    }
}
