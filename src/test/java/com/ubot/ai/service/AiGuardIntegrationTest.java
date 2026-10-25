package com.ubot.ai.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.*;

import com.ubot.ai.config.AiInputGuardProperties;
import com.ubot.ai.config.AiOutputGuardProperties;
import com.ubot.ai.dto.AnswerMaterials;
import com.ubot.ai.dto.ContextSection;
import com.ubot.ai.dto.ConversationTurn;
import com.ubot.ai.dto.OutputGuardResult;
import com.ubot.ai.tool.AiToolRegistry;
import com.ubot.chat.exception.ChatErrorCode;
import com.ubot.chat.service.ChatInputGuardService;
import com.ubot.chat.service.ChatPromptInjectionService;
import com.ubot.common.GlobalException;
import com.ubot.forbiddenword.exception.ForbiddenWordErrorCode;
import com.ubot.forbiddenword.exception.ForbiddenWordException;
import com.ubot.forbiddenword.service.ForbiddenWordFilterService;
import com.ubot.llm.dto.request.LlmRequestDto;
import com.ubot.llm.dto.response.LlmResponseDto;
import com.ubot.llm.exception.LlmErrorCode;
import com.ubot.llm.exception.LlmException;
import com.ubot.llm.service.LlmService;
import com.ubot.prompt.format.AiGuardFormat;
import com.ubot.prompt.format.AiOutputGuardAssessment;
import com.ubot.prompt.service.AiGuardPromptService;
import com.ubot.prompt.service.PromptService;
import com.ubot.prompt.service.SubQuestionPromptService;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.json.JsonMapper;

/** 모의 모델 응답만 사용합니다. 실제 LLM·DB·임베딩 호출은 없습니다. */
class AiGuardIntegrationTest {
    private final ForbiddenWordFilterService words = mock(ForbiddenWordFilterService.class);
    private final AiGuardPromptService prompts = new AiGuardPromptService(words);
    private final AiGuardModelService model = mock(AiGuardModelService.class);
    private final ChatInputGuardService codeInput = new ChatInputGuardService(words, new ChatPromptInjectionService(),
            new AiInputGuardProperties(), org.mockito.Mockito.mock(com.ubot.guardreview.service.ReviewCheckService.class));
    private final AiInputGuardService input = new AiInputGuardService(model, prompts, codeInput,
            new AiInputGuardProperties(), mock(SubQuestionPromptService.class), mock(LlmService.class), org.mockito.Mockito.mock(com.ubot.guardreview.service.ReviewCheckService.class));
    private final AiLlmOutputGuardService output = new AiLlmOutputGuardService(model, prompts, org.mockito.Mockito.mock(com.ubot.guardreview.service.ReviewCheckService.class));
    private static final String QUESTION = "로밍 이용 방법을 알려 주세요.";
    private static final String ANSWER = "로밍은 앱에서 신청할 수 있습니다.";

    @Test
    void 코드에서_차단하면_정상_질문이_섞여도_AI를_호출하지_않는다() {
        doThrow(new ForbiddenWordException(ForbiddenWordErrorCode.FORBIDDEN_WORD_DETECTED))
                .when(words).validateForbiddenWord(any());
        assertThatThrownBy(() -> input.inspect("문제 표현. " + QUESTION, QUESTION, List.of()))
                .isInstanceOf(ForbiddenWordException.class);
        verifyNoInteractions(model);
    }

    @Test
    void 통합형은_원문과_실제_문맥을_보내고_한_번의_판정에서_검색어를_받는다() {
        var history = List.of(new ConversationTurn("기존 질문", "기존 답변"));
        when(model.generate(any())).thenReturn(new LlmResponseDto(
                "{\"decision\":\"ALLOW\",\"reason\":\"SAFE\",\"sub_questions\":[\"로밍 신청 방법\"]}"));
        assertThat(input.inspect(QUESTION, QUESTION, history)).containsExactly("로밍 신청 방법");
        var request = ArgumentCaptor.forClass(LlmRequestDto.class);
        verify(model).generate(request.capture());
        var data = JsonMapper.builder().build().readTree(request.getValue().messages().getLast().content());
        assertThat(data.get("original_question").asString()).isEqualTo(QUESTION);
        assertThat(data.get("history").size()).isEqualTo(1);
        assertThat(request.getValue().generationOptions().maxTokens()).isEqualTo(512);
        assertThat(request.getValue().generationOptions().schemaName()).isEqualTo("input_safety_and_planning");
    }

    @Test
    void 입력_AI_차단과_보류를_구분하고_정상_부분만_통과시키지_않는다() {
        when(model.generate(any())).thenReturn(new LlmResponseDto("{\"decision\":\"BLOCK\",\"reason\":\"INJECTION\",\"sub_questions\":[]}"));
        assertThatThrownBy(() -> input.inspect(QUESTION, QUESTION, List.of()))
                .isInstanceOfSatisfying(GlobalException.class, e -> assertThat(e.getErrorCode()).isEqualTo(ChatErrorCode.PROMPT_INJECTION_DETECTED));
        when(model.generate(any())).thenReturn(new LlmResponseDto("{\"decision\":\"REVIEW\",\"reason\":\"UNCERTAIN\",\"sub_questions\":[]}"));
        assertThatThrownBy(() -> input.inspect(QUESTION, QUESTION, List.of()))
                .isInstanceOfSatisfying(LlmException.class, e -> assertThat(e.getErrorCode()).isEqualTo(LlmErrorCode.LLM_GUARD_REVIEW_REQUIRED));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "not json", "{\"decision\":\"ALLOW\",\"reason\":\"SAFE\"}",
            "{\"decision\":\"BLOCK\",\"reason\":\"HARM\",\"sub_questions\":[\"정상 부분\"]}",
            "{\"decision\":\"ALLOW\",\"reason\":\"SAFE\",\"sub_questions\":[]}",
            "{\"decision\":\"ALLOW\",\"decision\":\"BLOCK\",\"reason\":\"HARM\",\"sub_questions\":[]}",
            "{\"decision\":\"ALLOW\",\"reason\":\"SAFE\",\"sub_questions\":[\"정상\"]} {}"})
    void 잘못된_통합_판정은_허용이나_원문_검색으로_바꾸지_않는다(String response) {
        when(model.generate(any())).thenReturn(new LlmResponseDto(response));
        assertThatThrownBy(() -> input.inspect(QUESTION, QUESTION, List.of()))
                .isInstanceOfSatisfying(LlmException.class, e -> assertThat(e.getErrorCode()).isEqualTo(LlmErrorCode.LLM_RESPONSE_INVALID));
    }

    @Test
    void 타임아웃을_보류나_허용으로_바꾸거나_내부에서_재시도하지_않는다() {
        when(model.generate(any())).thenThrow(new LlmException(LlmErrorCode.LLM_TIMEOUT));
        assertThatThrownBy(() -> input.inspect(QUESTION, QUESTION, List.of()))
                .isInstanceOfSatisfying(LlmException.class, e -> assertThat(e.getErrorCode()).isEqualTo(LlmErrorCode.LLM_TIMEOUT));
        verify(model, times(1)).generate(any());
    }

    @ParameterizedTest
    @ValueSource(strings = {"BLOCK", "REVIEW"})
    void 출력_AI의_차단과_보류는_답변_전체를_종료한다(String decision) {
        String certainty = "BLOCK".equals(decision) ? "CONFIRMED" : "UNCERTAIN";
        when(model.generate(any())).thenReturn(new LlmResponseDto("{\"findings\":[{\"certainty\":\"" + certainty
                + "\",\"type\":\"UNSUPPORTED_CLAIM\",\"answer_quote\":\"" + ANSWER + "\"}]}"));
        assertThatThrownBy(() -> output.validate(ANSWER, List.of("잘못 적은 번호"), materials(), null))
                .isInstanceOfSatisfying(LlmException.class, e -> assertThat(e.getErrorCode()).isEqualTo(
                        "BLOCK".equals(decision) ? LlmErrorCode.LLM_OUTPUT_REJECTED : LlmErrorCode.LLM_GUARD_REVIEW_REQUIRED));
        verify(model, times(1)).generate(any());
    }

    @Test
    void 출력_허용은_답변을_수정하지_않으며_원문_없는_사유만_로그에_남긴다() {
        var logger = (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(AiLlmOutputGuardService.class);
        var appender = new ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>();
        appender.start(); logger.addAppender(appender);
        try {
            when(model.generate(any())).thenReturn(new LlmResponseDto("{\"findings\":[]}"));
            output.validate(ANSWER, List.of("9999"), materials(), null);
            assertThat(appender.list).allSatisfy(event -> assertThat(event.getFormattedMessage())
                    .doesNotContain(ANSWER, QUESTION, "PRIVATE_MODEL_TEXT"));
        } finally { logger.detachAppender(appender); appender.stop(); }
    }

    @ParameterizedTest
    @ValueSource(strings = {"CONFIRMED", "UNCERTAIN"})
    void 출력_차단과_보류의_인용_원문을_오류나_로그에_노출하지_않는다(String certainty) {
        String privateAnswer = "PRIVATE_MODEL_TEXT 로밍 신청 안내입니다.";
        var logger = (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(AiLlmOutputGuardService.class);
        var appender = new ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>();
        appender.start(); logger.addAppender(appender);
        try {
            when(model.generate(any())).thenReturn(new LlmResponseDto("{\"findings\":[{\"certainty\":\"" + certainty
                    + "\",\"type\":\"UNSUPPORTED_CLAIM\",\"answer_quote\":\"PRIVATE_MODEL_TEXT\"}]}"));
            assertThatThrownBy(() -> output.validate(privateAnswer, List.of(), materials(), null))
                    .isInstanceOfSatisfying(LlmException.class, error -> {
                        assertThat(error.getErrorCode()).isEqualTo("CONFIRMED".equals(certainty)
                                ? LlmErrorCode.LLM_OUTPUT_REJECTED : LlmErrorCode.LLM_GUARD_REVIEW_REQUIRED);
                        assertThat(error.getMessage()).doesNotContain("PRIVATE_MODEL_TEXT");
                        assertThat(error.getCause()).isNull();
                    });
            assertThat(appender.list).allSatisfy(event -> assertThat(event.getFormattedMessage())
                    .doesNotContain(privateAnswer, QUESTION, "PRIVATE_MODEL_TEXT"));
            verify(model, times(1)).generate(any());
        } finally { logger.detachAppender(appender); appender.stop(); }
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{\"decision\":\"ALLOW\",\"reason_codes\":[\"FALSE_EXECUTION\"],\"explanation\":\"정상 답변\"}",
            "{\"findings\":[],\"decision\":\"ALLOW\"}",
            "{\"findings\":[{\"certainty\":\"CONFIRMED\",\"type\":\"FALSE_EXECUTION\",\"answer_quote\":\"원문에 없는 인용\"}]}"
    })
    void 잘못된_출력_판정을_허용하거나_재시도하지_않는다(String response) {
        when(model.generate(any())).thenReturn(new LlmResponseDto(response));
        assertThatThrownBy(() -> output.validate(ANSWER, List.of(), materials(), null))
                .isInstanceOfSatisfying(LlmException.class,
                        error -> assertThat(error.getErrorCode()).isEqualTo(LlmErrorCode.LLM_RESPONSE_INVALID));
        verify(model, times(1)).generate(any());
    }

    @Test
    void 답변_생성_코드검사_AI검사_순서를_지키며_코드_거절이면_AI를_부르지_않는다() {
        var generation = mock(LlmService.class);
        var prompt = mock(PromptService.class);
        var tools = mock(AiToolRegistry.class);
        var code = mock(AiOutputGuardService.class);
        var ai = new AiService(prompt,
                generation,
                tools,
                code,
                com.ubot.ai.service.SubQuestionLabels.withoutMorphology(),
                output,
                new AiOutputGuardProperties(), org.mockito.Mockito.mock(com.ubot.guardreview.service.ReviewCaptureService.class), org.mockito.Mockito.mock(com.ubot.guardreview.service.ReviewCheckService.class));
        when(generation.generateAnswer(any())).thenReturn(new LlmResponseDto("{\"answer\":\""+ANSWER+"\",\"evidence_ids\":[],\"status\":\"ANSWER\"}"));
        when(prompt.createSlotPrompt(any(), any(), any(), any(), any())).thenReturn(new LlmRequestDto(List.of()));
        when(code.inspectAnswer(any(), any(), any(), any())).thenReturn(new OutputGuardResult(ANSWER, false, Set.of()));
        when(model.generate(any())).thenReturn(new LlmResponseDto("{\"findings\":[]}"));
        assertThat(ai.generateAnswer(materials()).answer()).isEqualTo(ANSWER);
        var order = inOrder(generation, code, model);
        order.verify(generation).generateAnswer(any()); order.verify(code).inspectAnswer(any(), any(), any(), any()); order.verify(model).generate(any());
        clearInvocations(model);
        when(code.inspectAnswer(any(), any(), any(), any())).thenReturn(new OutputGuardResult(null, true, Set.of(OutputGuardResult.Reason.POLICY_VIOLATION)));
        assertThatThrownBy(() -> ai.generateAnswer(materials())).isInstanceOf(LlmException.class);
        verifyNoInteractions(model);
    }

    @Test
    void 공식_FAQ_캐시는_코드만_검사하고_출력_AI와_답변_생성을_호출하지_않는다() {
        var generation = mock(LlmService.class);
        var code = mock(AiOutputGuardService.class);
        var ai = new AiService(mock(PromptService.class),
                generation,
                mock(AiToolRegistry.class),
                code,
                com.ubot.ai.service.SubQuestionLabels.withoutMorphology(),
                output,
                new AiOutputGuardProperties(), org.mockito.Mockito.mock(com.ubot.guardreview.service.ReviewCaptureService.class), org.mockito.Mockito.mock(com.ubot.guardreview.service.ReviewCheckService.class));
        when(code.inspectAnswer(any(), any(), any(), any())).thenReturn(new OutputGuardResult(ANSWER, false, Set.of()));
        assertThat(ai.validateCachedAnswer(materials(), ANSWER, "1").answer()).isEqualTo(ANSWER);
        verify(code).inspectAnswer(any(), any(), any(), any());
        verifyNoInteractions(model, generation);
    }

    @Test
    void 실제_출력_코드가_전체를_차단하면_후단_AI를_호출하지_않는다() {
        var generation = mock(LlmService.class);
        var prompt = mock(PromptService.class);
        var code = new AiOutputGuardService(words);
        var ai = new AiService(prompt,
                generation,
                mock(AiToolRegistry.class),
                code,
                com.ubot.ai.service.SubQuestionLabels.withoutMorphology(),
                output,
                new AiOutputGuardProperties(), org.mockito.Mockito.mock(com.ubot.guardreview.service.ReviewCaptureService.class), org.mockito.Mockito.mock(com.ubot.guardreview.service.ReviewCheckService.class));
        when(prompt.createSlotPrompt(any(), any(), any(), any(), any())).thenReturn(new LlmRequestDto(List.of()));
        when(generation.generateAnswer(any())).thenReturn(new LlmResponseDto("{\"answer\":\"" + ANSWER
                + " 환불 처리가 완료되었습니다.\",\"evidence_ids\":[],\"status\":\"ANSWER\"}"));

        assertThatThrownBy(() -> ai.generateAnswer(materials()))
                .isInstanceOfSatisfying(LlmException.class,
                        error -> assertThat(error.getErrorCode()).isEqualTo(LlmErrorCode.LLM_OUTPUT_REJECTED));
        verify(generation, times(1)).generateAnswer(any());
        verifyNoInteractions(model);
    }

    @ParameterizedTest
    @ValueSource(strings = {"1", "잘못 적은 번호"})
    void 실제_출력_코드를_통과한_캐시는_AI_없이_원문을_유지한다(String evidenceId) {
        var generation = mock(LlmService.class);
        var code = new AiOutputGuardService(words);
        var ai = new AiService(mock(PromptService.class),
                generation,
                mock(AiToolRegistry.class),
                code,
                com.ubot.ai.service.SubQuestionLabels.withoutMorphology(),
                output,
                new AiOutputGuardProperties(), org.mockito.Mockito.mock(com.ubot.guardreview.service.ReviewCaptureService.class), org.mockito.Mockito.mock(com.ubot.guardreview.service.ReviewCheckService.class));
        assertThat(ai.validateCachedAnswer(materials(), ANSWER, evidenceId).answer()).isEqualTo(ANSWER);
        verifyNoInteractions(model, generation);
    }

    @ParameterizedTest
    @ValueSource(strings = {"\n", "\r\n"})
    void 공식_캐시의_끝_개행은_코드_검사_후에도_AI_호출_없이_유지한다(String newline) {
        var generation = mock(LlmService.class);
        var ai = new AiService(mock(PromptService.class), generation, mock(AiToolRegistry.class),
                new AiOutputGuardService(words), SubQuestionLabels.withoutMorphology(), output,
                new AiOutputGuardProperties(), org.mockito.Mockito.mock(com.ubot.guardreview.service.ReviewCaptureService.class), org.mockito.Mockito.mock(com.ubot.guardreview.service.ReviewCheckService.class));
        String original = ANSWER + newline;
        var result = ai.validateCachedAnswer(materials(), original, "1");

        assertThat(result.answer()).isEqualTo(original);
        assertThat(result.outputGuardResult().answer()).isEqualTo(original);
        verifyNoInteractions(model, generation);
    }

    @Test
    void 캐시_본문의_검사결과는_개행_보존을_이유로_원문으로_되돌리지_않는다() {
        var code = mock(AiOutputGuardService.class);
        var ai = new AiService(mock(PromptService.class), mock(LlmService.class), mock(AiToolRegistry.class),
                code, SubQuestionLabels.withoutMorphology(), output, new AiOutputGuardProperties(), org.mockito.Mockito.mock(com.ubot.guardreview.service.ReviewCaptureService.class), org.mockito.Mockito.mock(com.ubot.guardreview.service.ReviewCheckService.class));
        var inspected = new OutputGuardResult(ANSWER, false, Set.of(OutputGuardResult.Reason.FORMAT_ERROR));
        when(code.inspectAnswer(any(), any(), any(), any())).thenReturn(inspected);
        var result = ai.validateCachedAnswer(materials(), "```\n" + ANSWER + "\n```\n", "1");

        assertThat(result.answer()).isEqualTo(ANSWER);
        assertThat(result.outputGuardResult()).isSameAs(inspected);
        verifyNoInteractions(model);
    }

    @Test
    void 위험한_캐시는_개행이_있어도_전체_차단하고_AI를_부르지_않는다() {
        var ai = new AiService(mock(PromptService.class), mock(LlmService.class), mock(AiToolRegistry.class),
                new AiOutputGuardService(words), SubQuestionLabels.withoutMorphology(), output,
                new AiOutputGuardProperties(), org.mockito.Mockito.mock(com.ubot.guardreview.service.ReviewCaptureService.class), org.mockito.Mockito.mock(com.ubot.guardreview.service.ReviewCheckService.class));

        assertThatThrownBy(() -> ai.validateCachedAnswer(materials(), ANSWER + " 환불 처리가 완료되었습니다.\n", "1"))
                .isInstanceOfSatisfying(LlmException.class,
                        error -> assertThat(error.getErrorCode()).isEqualTo(LlmErrorCode.LLM_OUTPUT_REJECTED));
        verifyNoInteractions(model);
    }

    @Test
    void 기존_통합_모의응답을_재설정해도_출력_모의응답은_유지한다() {
        AiGuardTestSupport.allow(model);
        var planned = new LlmResponseDto(
                "{\"decision\":\"ALLOW\",\"reason\":\"SAFE\",\"sub_questions\":[\"질문1\",\"질문2\"]}");
        doReturn(planned).when(model).generate(argThat(this::isInputRequest));

        assertThat(AiGuardFormat.parseInput(model.generate(prompts.input(QUESTION, QUESTION, List.of())).answer())
                .subQuestions()).containsExactly("질문1", "질문2");
        assertThat(AiOutputGuardAssessment.parse(model.generate(prompts.output(ANSWER, List.of("1"), materials(), null))
                .answer(), ANSWER).decision()).isEqualTo(AiGuardFormat.Decision.ALLOW);
    }

    @Test
    void 기존_통합_모의응답을_타임아웃으로_재설정할_수_있다() {
        AiGuardTestSupport.allow(model);
        doThrow(new LlmException(LlmErrorCode.LLM_TIMEOUT)).when(model).generate(argThat(this::isInputRequest));

        assertThatThrownBy(() -> model.generate(prompts.input(QUESTION, QUESTION, List.of())))
                .isInstanceOfSatisfying(LlmException.class,
                        error -> assertThat(error.getErrorCode()).isEqualTo(LlmErrorCode.LLM_TIMEOUT));
        verify(model, times(1)).generate(any());
    }

    private boolean isInputRequest(LlmRequestDto request) {
        return request != null && request.generationOptions() != null
                && "input_safety_and_planning".equals(request.generationOptions().schemaName());
    }

    @Test
    void 출력_AI_타임아웃도_허용이나_재시도로_바꾸지_않는다() {
        when(model.generate(any())).thenThrow(new LlmException(LlmErrorCode.LLM_TIMEOUT));

        assertThatThrownBy(() -> output.validate(ANSWER, List.of(), materials(), null))
                .isInstanceOfSatisfying(LlmException.class,
                        error -> assertThat(error.getErrorCode()).isEqualTo(LlmErrorCode.LLM_TIMEOUT));
        verify(model, times(1)).generate(any());
    }

    @Test
    void 출력_판정의_알수없는_필드나_사유와_잘린_JSON은_실패다() {
        for (String bad : List.of("{}", "{\"decision\":\"ALLOW\"}",
                "{\"decision\":\"ALLOW\",\"reason_codes\":[\"UNKNOWN\"],\"explanation\":\"x\"}",
                "{\"decision\":\"ALLOW\",\"reason_codes\":[\"SAFE\"],\"explanation\":\"x\",\"answer\":\"rewrite\"}")) {
            assertThatThrownBy(() -> AiGuardFormat.parseOutput(bad)).isInstanceOf(LlmException.class);
        }
    }

    private AnswerMaterials materials() {
        return AnswerMaterials.builder(QUESTION).addSection(new ContextSection("공식 안내", ANSWER)).build();
    }
}
