package com.ubot.ai.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.ubot.ai.config.AiOutputGuardProperties;
import com.ubot.ai.dto.AnswerMaterials;
import com.ubot.ai.dto.ContextSection;
import com.ubot.ai.dto.OutputGuardResult;
import com.ubot.ai.dto.OutputGuardResult.Stage;
import com.ubot.ai.exception.AiOutputGuardException;
import com.ubot.ai.dto.SubQuestionFaqs;
import com.ubot.ai.tool.AiToolRegistry;
import com.ubot.guardreview.service.ReviewCaptureService;
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

/** 최신 칸 형식 답변과 가드 스위치·원본 수집의 연결만 확인합니다. 외부 서비스는 모두 대역입니다. */
class AiGuardMergedFlowTest {
    private static final String FIRST = "앱에서 요금을 납부할 수 있습니다.";
    private static final String SECOND = "고객센터에서도 납부 방법을 안내받을 수 있습니다.";
    private static final String BODY = FIRST + "\n\n" + SECOND;
    private static final String RAW = "{\"answers\":[{\"answer\":\"" + FIRST
            + "\",\"status\":\"ANSWER\"},{\"answer\":\"" + SECOND
            + "\",\"status\":\"ANSWER\"}],\"evidence_ids\":[]}";
    private final PromptService prompts = mock(PromptService.class);
    private final LlmService generation = mock(LlmService.class);
    private final AiToolRegistry tools = mock(AiToolRegistry.class);
    private final AiOutputGuardService code = mock(AiOutputGuardService.class);
    private final AiLlmOutputGuardService llm = mock(AiLlmOutputGuardService.class);
    private final ReviewCaptureService capture = mock(ReviewCaptureService.class);
    private final AiOutputGuardProperties properties = new AiOutputGuardProperties();
    private final AiService service = new AiService(prompts, generation, tools, code,
            SubQuestionLabels.withoutMorphology(), llm, properties, capture, org.mockito.Mockito.mock(com.ubot.guardreview.service.ReviewCheckService.class));
    private final AnswerMaterials materials = AnswerMaterials.builder("납부 방법과 문의 방법을 알려 주세요.")
            .addSection(new ContextSection("공식 안내", BODY))
            .subQuestions(List.of(new SubQuestionFaqs("납부 방법", List.of(), "납부 방법"),
                    new SubQuestionFaqs("문의 방법", List.of(), "문의 방법")))
            .build().withReviewAttempt(71L);

    @BeforeEach
    void prepare() {
        when(tools.resolve(any())).thenReturn(List.of());
        when(prompts.createSlotPrompt(any(), any(), any(), any(), any()))
                .thenReturn(new LlmRequestDto(List.of()));
        when(generation.generateAnswer(any())).thenReturn(new LlmResponseDto(RAW));
        when(capture.captureResponse(any(), eq(71L), eq(RAW))).thenReturn(91L);
        when(code.inspectAnswer(anyString(), any(), any(), any())).thenAnswer(invocation ->
                new OutputGuardResult(invocation.getArgument(0), false, Set.of()));
    }

    @Test
    void inspectsCodePerSlotThenChecksTheWholeFinalAnswerOnce() {
        var result = service.generateAnswer(materials);
        assertThat(result.answer()).isEqualTo(BODY);
        var order = inOrder(capture, code, llm);
        order.verify(capture).captureResponse(any(), eq(71L), eq(RAW));
        order.verify(code).inspectAnswer(eq(FIRST), any(), any(), any());
        order.verify(code).inspectAnswer(eq(SECOND), any(), any(), any());
        order.verify(llm).validate(eq(BODY), any(), any(), any());
        order.verify(capture).recordGuardResult(eq(91L), eq(BODY), any());
        verify(llm, times(1)).validate(any(), any(), any(), any());
    }

    @Test
    void omittedAbstainSlotAndGeneratedNoticeAreNotGuardFiltering() {
        String raw = RAW.replace(SECOND + "\",\"status\":\"ANSWER\"", SECOND + "\",\"status\":\"ABSTAIN\"");
        when(generation.generateAnswer(any())).thenReturn(new LlmResponseDto(raw));
        when(capture.captureResponse(any(), eq(71L), eq(raw))).thenReturn(91L);
        var result = service.generateAnswer(materials);
        assertThat(result.status()).isEqualTo("PARTIAL");
        assertThat(result.answer()).startsWith(FIRST).doesNotContain(SECOND);
        verify(capture).recordGuardResult(eq(91L), eq(BODY), org.mockito.ArgumentMatchers.argThat(saved ->
                !saved.contentRemoved() && !saved.rejected() && saved.answer().equals(result.answer())));
    }

    @Test
    void replacingModelTextWithRetrievedProductListingIsNotGuardFiltering() {
        when(tools.resolve(any())).thenReturn(List.of(mock(org.springframework.ai.tool.ToolCallback.class)));
        var input = AnswerMaterials.builder("요금제 종류")
                .enableTool(com.ubot.ai.tool.AiTool.PLAN_SEARCH).build().withReviewAttempt(71L);
        when(generation.generateAnswer(any())).thenAnswer(call -> {
            LlmRequestDto request = call.getArgument(0);
            var recorder = (com.ubot.ai.tool.product.ProductDataRecorder) request.toolContext()
                    .get(com.ubot.ai.tool.product.ProductDataRecorder.CONTEXT_KEY);
            var card = new com.ubot.ai.dto.ProductDataCards.ProductCard(com.ubot.ai.tool.product.ProductType.PLAN,
                    "검증 요금제", 10000, List.of(), List.of());
            recorder.record(card, "검증 요금제 월 10,000원");
            recorder.searched(new com.ubot.ai.tool.product.ProductListing("요금제", "월정액 낮은 순", 1, List.of(card)));
            return new LlmResponseDto(RAW);
        });
        var result = service.generateAnswer(input);
        assertThat(result.answer()).contains("검증 요금제").isNotEqualTo(BODY);
        verify(capture).recordGuardResult(eq(91L), eq(BODY), org.mockito.ArgumentMatchers.argThat(saved ->
                !saved.contentRemoved() && !saved.rejected() && saved.answer().equals(result.answer())));
    }

    @Test
    void disablingOutputSkipsBothGuardsButKeepsSlotsAndRawCapture() {
        properties.setEnabled(false);
        var result = service.generateAnswer(materials);
        assertThat(result.answer()).isEqualTo(BODY);
        assertThat(result.slotOutcomes()).hasSize(2);
        verifyNoInteractions(code, llm);
        verify(capture).captureResponse(any(), eq(71L), eq(RAW));
        verify(capture).recordGuardResult(eq(91L), eq(BODY),
                org.mockito.ArgumentMatchers.argThat(saved -> saved.stage() == Stage.DISABLED && !saved.rejected()));
    }

    @Test
    void disablingOnlyLlmStillChecksEveryAnsweredSlotWithCode() {
        properties.setLlmEnabled(false);
        assertThat(service.generateAnswer(materials).answer()).isEqualTo(BODY);
        verify(code, times(2)).inspectAnswer(any(), any(), any(), any());
        verifyNoInteractions(llm);
    }

    @ParameterizedTest
    @EnumSource(value = OutputGuardResult.Reason.class, names = {"POLICY_VIOLATION", "ANSWER_ERROR"})
    void codeRejectionUsesDevelopDeliveryPolicyAndPreservesCaptureMetadata(OutputGuardResult.Reason reason) {
        when(code.inspectAnswer(eq(FIRST), any(), any(), any()))
                .thenReturn(new OutputGuardResult(null, true, Set.of(reason)));
        if (reason == OutputGuardResult.Reason.POLICY_VIOLATION) {
            assertRejected();
            verifyNoInteractions(llm);
            verify(capture).recordGuardDecision(91L, BODY, ReviewCaptureService.Decision.REJECTED, Stage.CODE,
                    List.of(reason.name()));
        } else {
            var result = service.generateAnswer(materials);
            assertThat(result.status()).isEqualTo("PARTIAL");
            assertThat(result.answer()).startsWith(SECOND).doesNotContain(FIRST);
            assertThat(result.slotOutcomes()).containsExactly(new com.ubot.ai.dto.AiAnswer.SlotOutcome("ABSTAIN", true),
                    new com.ubot.ai.dto.AiAnswer.SlotOutcome("ANSWER", false));
            verify(llm, times(1)).validate(eq(result.answer()), any(), any(), any());
            verify(capture).recordGuardResult(eq(91L), eq(BODY),
                    org.mockito.ArgumentMatchers.argThat(saved -> saved.stage() == Stage.LLM && !saved.rejected()
                            && saved.contentRemoved() && saved.reasons().equals(Set.of(reason)) && saved.answer().equals(result.answer())));
        }
        verify(capture, org.mockito.Mockito.never()).recordFailure(any(), any(), any());
        verify(generation, times(1)).generateAnswer(any());
    }

    @Test
    void anLlmRejectionIsWholeAnswerRejectionWithoutGeneratingOrCheckingAgain() {
        doThrow(new AiOutputGuardException(LlmErrorCode.LLM_OUTPUT_REJECTED, Stage.LLM,
                List.of("UNSUPPORTED_CLAIM"))).when(llm)
                .validate(any(), any(), any(), any());
        assertRejected();
        verify(llm, times(1)).validate(eq(BODY), any(), any(), any());
        verify(generation, times(1)).generateAnswer(any());
        verify(capture).recordGuardDecision(91L, BODY, ReviewCaptureService.Decision.REJECTED, Stage.LLM,
                List.of("UNSUPPORTED_CLAIM"));
        verify(capture, org.mockito.Mockito.never()).recordFailure(any(), any(), any());
    }

    @Test
    void anLlmReviewKeepsItsReasonAndIsNotRecordedAsTechnicalFailure() {
        doThrow(new AiOutputGuardException(LlmErrorCode.LLM_GUARD_REVIEW_REQUIRED, Stage.LLM,
                List.of("INSUFFICIENT_EVIDENCE"))).when(llm).validate(any(), any(), any(), any());
        assertThatThrownBy(() -> service.generateAnswer(materials)).isInstanceOfSatisfying(LlmException.class,
                error -> assertThat(error.getErrorCode()).isEqualTo(LlmErrorCode.LLM_GUARD_REVIEW_REQUIRED));
        verify(capture).recordGuardDecision(91L, BODY, ReviewCaptureService.Decision.REVIEW, Stage.LLM,
                List.of("INSUFFICIENT_EVIDENCE"));
        verify(capture, org.mockito.Mockito.never()).recordFailure(any(), any(), any());
        verify(generation, times(1)).generateAnswer(any());
    }

    private void assertRejected() {
        assertThatThrownBy(() -> service.generateAnswer(materials)).isInstanceOfSatisfying(LlmException.class,
                exception -> assertThat(exception.getErrorCode()).isEqualTo(LlmErrorCode.LLM_OUTPUT_REJECTED));
    }
}
