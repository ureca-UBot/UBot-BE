package com.ubot.chat.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.ubot.ai.dto.ConversationTurn;
import com.ubot.ai.service.AiGuardModelService;
import com.ubot.ai.service.AiInputGuardService;
import com.ubot.chat.entity.AnswerAttemptsHistory;
import com.ubot.chat.repository.ConversationTurnRepository;
import com.ubot.forbiddenword.service.ForbiddenWordFilterService;
import com.ubot.llm.dto.response.LlmResponseDto;
import com.ubot.llm.exception.LlmErrorCode;
import com.ubot.llm.exception.LlmException;
import com.ubot.prompt.service.AiGuardPromptService;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

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
    void 원문_검색이나_질문정리_비활성화에서도_안전_검사를_생략하지_않는다(boolean enabled) {
        when(router.route(any(), anyBoolean())).thenReturn(QuestionRouter.Route.none());
        when(model.generate(any())).thenReturn(new LlmResponseDto("{\"decision\":\"ALLOW\",\"reason\":\"SAFE\"}"));
        var plan = planner(enabled).plan(attempt);
        assertThat(plan.searchFirst()).isTrue();
        assertThat(plan.searchQueries()).containsExactly(attempt.getQuestion());
        verify(model).generate(argThat(r -> r.generationOptions().schemaName().equals("input_safety")));
        verifyNoInteractions(guard);
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
