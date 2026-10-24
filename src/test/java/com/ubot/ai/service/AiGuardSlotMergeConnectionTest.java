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
import com.ubot.ai.dto.OutputGuardResult;
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
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;

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
    void retainsTheExistingSlotOrderAndContextWithCodeThenAiAtEachInspection() {
        prepare("ANSWER", "ANSWER");

        AiAnswer answer = service.generateAnswer(materials);

        assertThat(answer.answer()).isEqualTo(FIRST + "\n\n" + SECOND);
        assertThat(answer.status()).isEqualTo("ANSWER");
        assertThat(answer.slotOutcomes()).containsExactly(new AiAnswer.SlotOutcome("ANSWER", false),
                new AiAnswer.SlotOutcome("ANSWER", false));
        var order = inOrder(code, output);
        order.verify(code).inspectAnswer(eq(FIRST), any(), any(), any());
        order.verify(output).validate(eq(FIRST), any(), any(), any());
        order.verify(code).inspectAnswer(eq(SECOND), any(), any(), any());
        order.verify(output).validate(eq(SECOND), any(), any(), any());
        var scoped = ArgumentCaptor.forClass(AnswerMaterials.class);
        verify(output, times(2)).validate(any(), any(), scoped.capture(), any());
        assertThat(scoped.getAllValues()).extracting(AnswerMaterials::question)
                .containsExactly("요금은 어디서 확인하고", "매장 영업시간은 어디서 봐요?");
        assertThat(scoped.getAllValues()).allSatisfy(value -> assertThat(value.faqs()).isEqualTo(materials.faqs()));
        verify(prompts).createSlotPrompt(materials.question(), materials.faqs(), materials.subQuestions(),
                materials.sections(), materials.history());
        verify(prompts, never()).createPrompt(any(), any(), any(), any(), any());
        verify(generation, times(1)).generateAnswer(any());
    }

    @ParameterizedTest
    @EnumSource(value = OutputGuardResult.Reason.class, names = {"POLICY_VIOLATION", "ANSWER_ERROR", "FORMAT_ERROR"})
    void anyRejectedSlotStopsTheWholeAnswerBeforeItsAiCheck(OutputGuardResult.Reason reason) {
        prepare("ANSWER", "ANSWER");
        when(code.inspectAnswer(eq(FIRST), any(), any(), any()))
                .thenReturn(new OutputGuardResult(null, true, Set.of(reason)));

        assertRejected(LlmErrorCode.LLM_OUTPUT_REJECTED);

        verifyNoInteractions(output);
        verify(code, times(1)).inspectAnswer(any(), any(), any(), any());
        verify(generation, times(1)).generateAnswer(any());
    }

    @Test
    void aRejectedSecondSlotNeverReturnsTheFirstAnswerOrRetriesGeneration() {
        prepare("ANSWER", "ANSWER");
        when(code.inspectAnswer(eq(SECOND), any(), any(), any()))
                .thenReturn(new OutputGuardResult(null, true, Set.of(OutputGuardResult.Reason.ANSWER_ERROR)));

        assertRejected(LlmErrorCode.LLM_OUTPUT_REJECTED);

        verify(output).validate(eq(FIRST), any(), any(), any());
        verify(output, never()).validate(eq(SECOND), any(), any(), any());
        verify(generation, times(1)).generateAnswer(any());
    }

    @Test
    void removedContentCannotBecomeASuccessfulPartialAnswer() {
        prepare("ANSWER", "ANSWER");
        when(code.inspectAnswer(eq(FIRST), any(), any(), any()))
                .thenReturn(new OutputGuardResult("남은 안내", false,
                        Set.of(OutputGuardResult.Reason.ANSWER_ERROR), true));

        assertRejected(LlmErrorCode.LLM_OUTPUT_REJECTED);

        verifyNoInteractions(output);
    }

    @ParameterizedTest
    @EnumSource(value = LlmErrorCode.class, names = {"LLM_OUTPUT_REJECTED", "LLM_GUARD_REVIEW_REQUIRED",
            "LLM_TIMEOUT", "LLM_RESPONSE_INVALID"})
    void aiBlockReviewAndFailuresPropagateWithoutAllowOrRetry(LlmErrorCode error) {
        prepare("ANSWER", "ANSWER");
        doThrow(new LlmException(error)).when(output).validate(eq(FIRST), any(), any(), any());

        assertRejected(error);

        verify(code, times(1)).inspectAnswer(any(), any(), any(), any());
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
        verify(output, times(1)).validate(eq(FIRST), any(), any(), any());
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
