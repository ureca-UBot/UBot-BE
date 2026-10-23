package com.ubot.chat.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.ubot.ai.dto.ConversationTurn;
import com.ubot.ai.dto.AiAnswer;
import com.ubot.ai.service.AiGuardModelService;
import com.ubot.ai.service.AiInputGuardService;
import com.ubot.ai.service.AiService;
import com.ubot.chat.entity.AnswerAttemptsHistory;
import com.ubot.chat.repository.ConversationTurnRepository;
import com.ubot.forbiddenword.service.ForbiddenWordFilterService;
import com.ubot.faq.dto.response.FaqSearchResponseDto;
import com.ubot.faq.enums.Intent;
import com.ubot.faq.retrieval.FaqRetrievalService;
import com.ubot.llm.dto.response.LlmResponseDto;
import com.ubot.llm.exception.LlmErrorCode;
import com.ubot.llm.exception.LlmException;
import com.ubot.prompt.service.AiGuardPromptService;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.util.ReflectionTestUtils;

class AiInputPlannerConnectionTest {
    private final AiGuardModelService model = mock(AiGuardModelService.class);
    private final ConversationTurnRepository turns = mock(ConversationTurnRepository.class);
    private final ForbiddenWordFilterService words = mock(ForbiddenWordFilterService.class);
    private final ChatInputGuardService code = new ChatInputGuardService(words, new ChatPromptInjectionService());
    private final AiInputGuardService safety = new AiInputGuardService(model, new AiGuardPromptService(words), code);
    private final QuestionRouter router = mock(QuestionRouter.class);
    private final SubQuestionGuard guard = mock(SubQuestionGuard.class);
    private final MultiTurnDetector detector = mock(MultiTurnDetector.class);
    private final AnswerAttemptsHistory attempt = AnswerAttemptsHistory.builder().id(1L).userId(2L)
            .conversationId(3L).question("로밍 이용 방법").status("PENDING").build();

    private SubQuestionPlanner planner(boolean enabled) {
        return new SubQuestionPlanner(enabled, turns, safety, guard, router, detector, code);
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void 기존_원문검색이나_질문정리_비활성화_경로에서는_AI를_호출하지_않는다(boolean enabled) {
        when(router.route(any(), anyBoolean())).thenReturn(QuestionRouter.Route.none());
        var plan = planner(enabled).plan(attempt);
        assertThat(plan.searchFirst()).isTrue();
        assertThat(plan.searchQueries()).containsExactly(attempt.getQuestion());
        verifyNoInteractions(model, guard, detector);
    }

    @Test
    void 질문정리를_끄면_검색실패_뒤에도_기존처럼_원문을_유지하고_AI를_호출하지_않는다() {
        var disabled = planner(false);
        var first = disabled.plan(attempt);

        var retried = disabled.judge(attempt, first);

        assertThat(retried.searchQueries()).containsExactly(attempt.getQuestion());
        assertThat(retried.source()).isEqualTo(SubQuestionPlan.Source.ORIGINAL);
        verifyNoInteractions(model, guard, detector);
    }

    @Test
    void 원문검색이_충분하면_답변을_생성하되_통합_AI나_보조_AI를_호출하지_않는다() {
        when(router.route(any(), anyBoolean())).thenReturn(QuestionRouter.Route.none());
        var vectors = ChatTestFixtures.vectorService();
        var source = new FaqSearchResponseDto(1L, "로밍 이용 방법", "앱에서 신청합니다.", .9, Intent.GENERAL);
        when(vectors.getSimilarList(attempt.getQuestion(), 3)).thenReturn(List.of(source));
        var answer = mock(AiService.class);
        when(answer.generateAnswer(any())).thenReturn(new AiAnswer("앱에서 신청합니다.", null, false));
        var processor = processor(vectors, answer);

        var result = processor.generateAnswer(attempt, null, () -> { });

        assertThat(result.isFailed()).isFalse();
        assertThat(result.answer().answer()).isEqualTo("앱에서 신청합니다.");
        verify(vectors, times(1)).getSimilarList(attempt.getQuestion(), 3);
        verify(answer, times(1)).generateAnswer(any());
        verifyNoInteractions(model, guard, detector);
    }

    @Test
    void 기존_원문검색이_부족할_때만_통합_AI를_한번_호출하고_정리한_질문을_검색한다() {
        when(router.route(any(), anyBoolean())).thenReturn(QuestionRouter.Route.none());
        var vectors = ChatTestFixtures.vectorService();
        var weak = new FaqSearchResponseDto(1L, "로밍", "안내", .5, Intent.GENERAL);
        var sufficient = new FaqSearchResponseDto(2L, "로밍 신청 방법", "앱에서 신청합니다.", .9, Intent.GENERAL);
        when(vectors.getSimilarList(attempt.getQuestion(), 3)).thenReturn(List.of(weak));
        when(vectors.getSimilarList("로밍 신청 방법", 3)).thenReturn(List.of(sufficient));
        when(model.generate(any())).thenReturn(new LlmResponseDto(
                "{\"decision\":\"ALLOW\",\"reason\":\"SAFE\",\"sub_questions\":[\"로밍 신청 방법\"]}"));
        when(guard.check(any(), any(), any())).thenReturn(new SubQuestionGuard.Result(
                List.of(new SubQuestionGuard.Item("로밍 신청 방법", attempt.getQuestion())), false,
                java.util.EnumSet.noneOf(SubQuestionGuard.Reason.class)));
        var answer = mock(AiService.class);
        when(answer.generateAnswer(any())).thenReturn(new AiAnswer("앱에서 신청합니다.", null, false));

        var result = processor(vectors, answer).generateAnswer(attempt, null, () -> { });

        assertThat(result.isFailed()).isFalse();
        var order = inOrder(vectors, model, guard, detector, answer);
        order.verify(vectors).getSimilarList(attempt.getQuestion(), 3);
        order.verify(model).generate(argThat(request -> request.generationOptions().schemaName()
                .equals("input_safety_and_planning")));
        order.verify(guard).check(any(), any(), any());
        order.verify(detector).isMultiTurn(any(), any(), any(), any());
        order.verify(vectors).getSimilarList("로밍 신청 방법", 3);
        order.verify(answer).generateAnswer(any());
        verify(model, times(1)).generate(any());
    }

    private ChatAnswerProcessor processor(com.ubot.faq.service.FaqVectorService vectors, AiService answer) {
        var processor = ChatTestFixtures.processor(FaqRetrievalService.denseOnly(vectors), answer,
                ChatTestFixtures.collector(), planner(true), ChatTestFixtures.disabledCache(),
                ChatTestFixtures.passthroughSentenceFilter(), code);
        ReflectionTestUtils.setField(processor, "topK", 3);
        ReflectionTestUtils.setField(processor, "confidenceThreshold", .75);
        return processor;
    }

    @Test
    void 통합형은_허용_후에만_기존_검색어_보호와_멀티턴_판정을_사용한다() {
        var route = new QuestionRouter.Route(List.of("문장 여럿"), List.of());
        when(router.route(any(), anyBoolean())).thenReturn(route);
        when(turns.findPreviousTurns(1L, 3)).thenReturn(List.of(new ConversationTurn("로밍", "안내")));
        when(model.generate(any())).thenReturn(new LlmResponseDto("{\"decision\":\"ALLOW\",\"reason\":\"SAFE\",\"sub_questions\":[\"로밍 신청 방법\"]}"));
        when(guard.check(any(), any(), any())).thenReturn(new SubQuestionGuard.Result(
                List.of(new SubQuestionGuard.Item("로밍 신청 방법", attempt.getQuestion())), false,
                java.util.EnumSet.noneOf(SubQuestionGuard.Reason.class)));
        var plan = planner(true).plan(attempt);
        assertThat(plan.searchQueries()).containsExactly("로밍 신청 방법");
        var order = inOrder(model, guard, detector);
        order.verify(model).generate(any()); order.verify(guard).check(any(), any(), any());
        order.verify(detector).isMultiTurn(any(), any(), any(), eq(route));
    }

    @ParameterizedTest
    @ValueSource(strings = {"BLOCK", "REVIEW", "INVALID", "TIMEOUT"})
    void 통합_판정이_불확실하거나_실패하면_검색으로_넘기지_않는다(String state) {
        when(router.route(any(), anyBoolean())).thenReturn(new QuestionRouter.Route(List.of("문장 여럿"), List.of()));
        if (state.equals("TIMEOUT")) when(model.generate(any())).thenThrow(new LlmException(LlmErrorCode.LLM_TIMEOUT));
        else if (state.equals("INVALID")) when(model.generate(any())).thenReturn(new LlmResponseDto("invalid"));
        else when(model.generate(any())).thenReturn(new LlmResponseDto("{\"decision\":\""+state+"\",\"reason\":\""
                +(state.equals("REVIEW") ? "UNCERTAIN" : "HARM")+"\",\"sub_questions\":[]}"));
        assertThatThrownBy(() -> planner(true).plan(attempt)).isInstanceOf(com.ubot.common.GlobalException.class);
        verifyNoInteractions(guard, detector);
        verify(model, times(1)).generate(any());
    }

    @Test
    void 보류와_출력거절은_기존_재시도_검사에서_다시_시도하지_못한다() {
        var attempts = mock(ChatAttemptsService.class, CALLS_REAL_METHODS);
        for (var code : List.of(LlmErrorCode.LLM_OUTPUT_REJECTED, LlmErrorCode.LLM_GUARD_REVIEW_REQUIRED)) {
            var failed = AnswerAttemptsHistory.builder().id(1L).userId(2L).question("문의")
                    .attemptCount(1).status("FAIL").errorCode(code).build();
            org.springframework.test.util.ReflectionTestUtils.setField(attempts, "maxAttempts", 3);
            assertThat(attempts.validateRetryAttempt(failed)).contains(com.ubot.chat.exception.ChatErrorCode.RETRY_NOT_ALLOWED);
        }
    }
}
