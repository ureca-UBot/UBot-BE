package com.ubot.ai.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.ubot.ai.config.AiInputGuardProperties;
import com.ubot.ai.config.AiOutputGuardProperties;
import com.ubot.ai.dto.AnswerMaterials;
import com.ubot.ai.dto.ContextSection;
import com.ubot.ai.dto.OutputGuardResult;
import com.ubot.ai.tool.AiToolRegistry;
import com.ubot.chat.service.ChatInputGuardService;
import com.ubot.llm.dto.request.LlmRequestDto;
import com.ubot.llm.dto.response.LlmResponseDto;
import com.ubot.llm.exception.LlmErrorCode;
import com.ubot.llm.exception.LlmException;
import com.ubot.llm.service.LlmService;
import com.ubot.prompt.service.AiGuardPromptService;
import com.ubot.prompt.service.PromptService;
import com.ubot.prompt.service.SubQuestionPromptService;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** 실제 서버나 모델을 호출하지 않고 출력 검사 호출의 유무를 확인합니다. */
class AiOutputGuardSwitchTest {

    private static final String ANSWER = "정확한 요금제 이름을 알려주실 수 있나요?";
    private final PromptService prompts = mock(PromptService.class);
    private final LlmService generation = mock(LlmService.class);
    private final AiOutputGuardService code = mock(AiOutputGuardService.class);
    private final AiGuardModelService model = mock(AiGuardModelService.class);
    private final AiGuardPromptService guardPrompts = mock(AiGuardPromptService.class);
    private final AiOutputGuardProperties properties = new AiOutputGuardProperties();
    private final AiService service = new AiService(prompts,
                generation,
                mock(AiToolRegistry.class),
                code,
                com.ubot.ai.service.SubQuestionLabels.withoutMorphology(),
                new AiLlmOutputGuardService(model, guardPrompts),
                properties);
    private final AnswerMaterials materials = AnswerMaterials.builder("요금제 변경 방법을 알려 주세요.")
            .addSection(new ContextSection("공식 안내", "요금제는 앱에서 변경할 수 있습니다."))
            .build();

    @ParameterizedTest
    @ValueSource(strings = {"ANSWER", "ABSTAIN"})
    void disabledOutputGuardsPreserveTheParsedAnswerAndStatus(String status) {
        properties.setEnabled(false);
        prepareGeneration("{\"answer\":\"" + ANSWER + "\",\"evidence_ids\":[\"1\"],\"status\":\"" + status + "\"}");

        var answer = service.generateAnswer(materials);

        assertThat(answer.answer()).isEqualTo(ANSWER);
        assertThat(answer.status()).isEqualTo(status);
        assertThat(answer.outputGuardResult().rejected()).isFalse();
        assertThat(answer.outputGuardResult().reasons()).isEmpty();
        verify(generation, times(1)).generateAnswer(any());
        verifyNoInteractions(code, guardPrompts, model);
    }

    @Test
    void disabledCachedAnswersSkipBothGuardsAndAnswerGeneration() {
        properties.setEnabled(false);

        var answer = service.validateCachedAnswer(materials, ANSWER, "1");

        assertThat(answer.answer()).isEqualTo(ANSWER);
        assertThat(answer.outputGuardResult().reasons()).isEmpty();
        verifyNoInteractions(code, guardPrompts, model, prompts, generation);
    }

    @Test
    void disablingGuardsDoesNotBypassTheExistingAnswerJsonParser() {
        properties.setEnabled(false);
        prepareGeneration("{\"answer\":\"잘린 응답");

        assertThatThrownBy(() -> service.generateAnswer(materials))
                .isInstanceOfSatisfying(LlmException.class,
                        error -> assertThat(error.getErrorCode()).isEqualTo(LlmErrorCode.LLM_RESPONSE_INVALID));
        verifyNoInteractions(code, guardPrompts, model);
    }

    @Test
    void concurrentRequestsDoNotCreateOutputGuardPromptsOrAcquireModelSlotsWhenDisabled() throws Exception {
        properties.setEnabled(false);
        prepareGeneration("{\"answer\":\"" + ANSWER + "\",\"evidence_ids\":[],\"status\":\"ANSWER\"}");

        assertConcurrentAnswers();

        verify(generation, times(32)).generateAnswer(any());
        // 모델 호출 전에 대기 슬롯을 얻으므로, generate 자체가 0회면 출력 검사 슬롯도 사용하지 않습니다.
        verifyNoInteractions(code, guardPrompts, model);
    }

    @ParameterizedTest
    @ValueSource(strings = {"ANSWER", "ABSTAIN"})
    void disablingOnlyLlmKeepsCodeInspectionAndItsResult(String status) {
        properties.setLlmEnabled(false);
        prepareGeneration("{\"answer\":\"" + ANSWER + "\",\"evidence_ids\":[],\"status\":\"" + status + "\"}");
        var codeResult = new OutputGuardResult(ANSWER, false,
                Set.of(OutputGuardResult.Reason.USER_INFORMATION_OR_LOOKUP_MISSING));
        when(code.inspectAnswer(any(), any(), any(), any())).thenReturn(codeResult);

        var answer = service.generateAnswer(materials);

        assertThat(answer.answer()).isEqualTo(ANSWER);
        assertThat(answer.status()).isEqualTo(status);
        assertThat(answer.outputGuardResult()).isSameAs(codeResult);
        verify(code, times(1)).inspectAnswer(any(), any(), any(), any());
        verify(generation, times(1)).generateAnswer(any());
        verifyNoInteractions(guardPrompts, model);
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void cachedAnswersUseCodeOnlyWhetherTheLlmSwitchIsEnabledOrDisabled(boolean llmEnabled) {
        properties.setLlmEnabled(llmEnabled);
        var codeResult = new OutputGuardResult(ANSWER, false, Set.of());
        when(code.inspectAnswer(any(), any(), any(), any())).thenReturn(codeResult);

        var answer = service.validateCachedAnswer(materials, ANSWER, "1");

        assertThat(answer.answer()).isEqualTo(ANSWER);
        assertThat(answer.outputGuardResult()).isSameAs(codeResult);
        verify(code, times(1)).inspectAnswer(any(), any(), any(), any());
        verifyNoInteractions(guardPrompts, model, prompts, generation);
    }

    @Test
    void disablingLlmDoesNotBypassWholeAnswerRejectionByCode() {
        properties.setLlmEnabled(false);
        prepareGeneration("{\"answer\":\"차단할 답변\",\"evidence_ids\":[],\"status\":\"ANSWER\"}");
        when(code.inspectAnswer(any(), any(), any(), any()))
                .thenReturn(new OutputGuardResult(null, true, Set.of(OutputGuardResult.Reason.POLICY_VIOLATION)));

        assertThatThrownBy(() -> service.generateAnswer(materials))
                .isInstanceOfSatisfying(LlmException.class,
                        error -> assertThat(error.getErrorCode()).isEqualTo(LlmErrorCode.LLM_OUTPUT_REJECTED));
        verify(code, times(1)).inspectAnswer(any(), any(), any(), any());
        verify(generation, times(1)).generateAnswer(any());
        verifyNoInteractions(guardPrompts, model);
    }

    @Test
    void concurrentRequestsStillRunCodeButNeverCallLlmWhenOnlyLlmIsDisabled() throws Exception {
        properties.setLlmEnabled(false);
        prepareGeneration("{\"answer\":\"" + ANSWER + "\",\"evidence_ids\":[],\"status\":\"ANSWER\"}");
        when(code.inspectAnswer(any(), any(), any(), any()))
                .thenReturn(new OutputGuardResult(ANSWER, false, Set.of()));

        assertConcurrentAnswers();

        verify(code, times(32)).inspectAnswer(any(), any(), any(), any());
        verify(generation, times(32)).generateAnswer(any());
        verifyNoInteractions(guardPrompts, model);
    }

    @Test
    void theWholeOutputSwitchAlsoSkipsCodeWhenLlmIsDisabled() {
        properties.setEnabled(false);
        properties.setLlmEnabled(false);

        assertThat(service.validateCachedAnswer(materials, ANSWER, "1").answer()).isEqualTo(ANSWER);

        verifyNoInteractions(code, guardPrompts, model, prompts, generation);
    }

    @Test
    void theDefaultEnabledPathStillRunsCodeThenLlmGuard() {
        prepareGeneration("{\"answer\":\"" + ANSWER + "\",\"evidence_ids\":[],\"status\":\"ANSWER\"}");
        when(code.inspectAnswer(any(), any(), any(), any()))
                .thenReturn(new OutputGuardResult(ANSWER, false, Set.of()));
        when(guardPrompts.output(any(), any(), any(), any())).thenReturn(new LlmRequestDto(List.of()));
        when(model.generate(any())).thenReturn(new LlmResponseDto(
                "{\"decision\":\"ALLOW\",\"reason_codes\":[],\"explanation\":\"정상 안내\"}"));

        assertThat(service.generateAnswer(materials).answer()).isEqualTo(ANSWER);

        var order = inOrder(generation, code, guardPrompts, model);
        order.verify(generation).generateAnswer(any());
        order.verify(code).inspectAnswer(any(), any(), any(), any());
        order.verify(guardPrompts).output(any(), any(), any(), any());
        order.verify(model).generate(any());
    }

    @Test
    void theEnabledCodeGuardStillRejectsTheWholeAnswerWithoutCallingLlm() {
        prepareGeneration("{\"answer\":\"차단할 답변\",\"evidence_ids\":[],\"status\":\"ANSWER\"}");
        when(code.inspectAnswer(any(), any(), any(), any()))
                .thenReturn(new OutputGuardResult(null, true, Set.of(OutputGuardResult.Reason.POLICY_VIOLATION)));

        assertThatThrownBy(() -> service.generateAnswer(materials))
                .isInstanceOfSatisfying(LlmException.class,
                        error -> assertThat(error.getErrorCode()).isEqualTo(LlmErrorCode.LLM_OUTPUT_REJECTED));
        verify(generation, times(1)).generateAnswer(any());
        verifyNoInteractions(guardPrompts, model);
    }

    @Test
    void disablingOutputGuardsDoesNotDisableTheSharedInputAi() {
        properties.setEnabled(false);
        var inputCode = mock(ChatInputGuardService.class);
        var input = new AiInputGuardService(model, guardPrompts, inputCode, new AiInputGuardProperties(),
                mock(SubQuestionPromptService.class), mock(LlmService.class));
        when(guardPrompts.input(any(), any(), any())).thenReturn(new LlmRequestDto(List.of()));
        when(model.generate(any())).thenReturn(new LlmResponseDto(
                "{\"decision\":\"ALLOW\",\"reason\":\"SAFE\",\"sub_questions\":[\"요금제 변경 방법\"]}"));

        assertThat(service.validateCachedAnswer(materials, ANSWER, "1").answer()).isEqualTo(ANSWER);
        assertThat(input.inspect(materials.question(), materials.question(), List.of()))
                .containsExactly("요금제 변경 방법");

        verify(inputCode).prepareQuestion(materials.question());
        verify(model, times(1)).generate(any());
        verify(guardPrompts).input(any(), any(), any());
        verifyNoInteractions(code, generation);
    }

    private void assertConcurrentAnswers() throws Exception {
        var requests = new ArrayList<Callable<String>>();
        for (int i = 0; i < 32; i++) {
            requests.add(() -> service.generateAnswer(materials).answer());
        }
        try (var executor = Executors.newFixedThreadPool(8)) {
            for (var result : executor.invokeAll(requests, 10, TimeUnit.SECONDS)) {
                assertThat(result.isCancelled()).isFalse();
                assertThat(result.get()).isEqualTo(ANSWER);
            }
        }
    }

    private void prepareGeneration(String response) {
        when(prompts.createSlotPrompt(any(), any(), any(), any(), any())).thenReturn(new LlmRequestDto(List.of()));
        when(generation.generateAnswer(any())).thenReturn(new LlmResponseDto(response));
    }
}
