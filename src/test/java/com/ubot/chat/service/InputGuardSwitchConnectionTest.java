package com.ubot.chat.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.ubot.ai.config.AiInputGuardProperties;
import com.ubot.ai.dto.ConversationTurn;
import com.ubot.ai.service.AiGuardModelService;
import com.ubot.ai.service.AiInputGuardService;
import com.ubot.chat.entity.AnswerAttemptsHistory;
import com.ubot.chat.exception.ChatErrorCode;
import com.ubot.chat.exception.ChatException;
import com.ubot.chat.repository.ConversationTurnRepository;
import com.ubot.forbiddenword.exception.ForbiddenWordErrorCode;
import com.ubot.forbiddenword.exception.ForbiddenWordException;
import com.ubot.forbiddenword.service.ForbiddenWordFilterService;
import com.ubot.llm.dto.request.LlmRequestDto;
import com.ubot.llm.dto.response.LlmResponseDto;
import com.ubot.llm.exception.LlmErrorCode;
import com.ubot.llm.exception.LlmException;
import com.ubot.llm.service.LlmService;
import com.ubot.prompt.format.SubQuestionFormat;
import com.ubot.prompt.service.AiGuardPromptService;
import com.ubot.prompt.service.SubQuestionPromptService;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.core.io.ClassPathResource;

/** 실제 DB·모델 없이 입력 검사 스위치와 기존 분류 경로의 연결을 확인합니다. */
class InputGuardSwitchConnectionTest {

    private static final String QUESTION = "로밍 신청과 해지 방법을 알려 주세요.";
    private static final List<ConversationTurn> HISTORY = List.of(new ConversationTurn("일본 로밍", "앱에서 신청합니다."));
    private static final List<String> QUERIES = List.of("로밍 신청 방법", "로밍 해지 방법");
    private static final String PLANNED = "{\"sub_questions\":[\"로밍 신청 방법\",\"로밍 해지 방법\"]}";
    private final AiInputGuardProperties properties = new AiInputGuardProperties();
    private final ForbiddenWordFilterService words = mock(ForbiddenWordFilterService.class);
    private final ChatPromptInjectionService injection = mock(ChatPromptInjectionService.class);
    private final ChatInputGuardService code = new ChatInputGuardService(words, injection, properties, org.mockito.Mockito.mock(com.ubot.guardreview.service.ReviewCheckService.class));
    private final AiGuardModelService safetyModel = mock(AiGuardModelService.class);
    private final AiGuardPromptService safetyPrompts = mock(AiGuardPromptService.class);
    private final SubQuestionPromptService planningPrompts = mock(SubQuestionPromptService.class);
    private final LlmService planningLlm = mock(LlmService.class);
    private final AiInputGuardService input = new AiInputGuardService(safetyModel, safetyPrompts, code,
            properties, planningPrompts, planningLlm, org.mockito.Mockito.mock(com.ubot.guardreview.service.ReviewCheckService.class));
    private final LlmRequestDto request = new LlmRequestDto(List.of());
    private final ConversationTurnRepository turns = mock(ConversationTurnRepository.class);
    private final QuestionRouter router = mock(QuestionRouter.class);
    private final SubQuestionGuard queryGuard = mock(SubQuestionGuard.class);
    private final MultiTurnDetector detector = mock(MultiTurnDetector.class);
    private final AnswerAttemptsHistory attempt = AnswerAttemptsHistory.builder().id(1L).userId(2L)
            .conversationId(3L).question(QUESTION).status("PENDING").build();

    @ParameterizedTest
    @CsvSource({"true,true", "true,false", "false,true", "false,false"})
    void switchCombinationsKeepPlanningAndOnlyRunTheEnabledSafetyChecks(boolean enabled, boolean llmEnabled) {
        properties.setEnabled(enabled);
        properties.setLlmEnabled(llmEnabled);
        if (enabled && llmEnabled) {
            when(safetyPrompts.input(QUESTION, QUESTION, HISTORY)).thenReturn(request);
            when(safetyModel.generate(request)).thenReturn(new LlmResponseDto(
                    "{\"decision\":\"ALLOW\",\"reason\":\"SAFE\",\"sub_questions\":[\"로밍 신청 방법\",\"로밍 해지 방법\"]}"));
        } else {
            preparePlanning();
        }

        assertThat(input.inspect(QUESTION, QUESTION, HISTORY)).containsExactlyElementsOf(QUERIES);

        if (enabled) {
            verify(words, atLeastOnce()).validateForbiddenWord(any());
            verify(injection).validatePreparedRequests(any());
        } else {
            verifyNoInteractions(words, injection);
        }
        if (enabled && llmEnabled) {
            verify(safetyModel, times(1)).generate(request);
            verifyNoInteractions(planningPrompts, planningLlm);
        } else {
            verify(planningPrompts).createPrompt(QUESTION, HISTORY);
            verify(planningLlm, times(1)).generateJudgment(request);
            verifyNoInteractions(safetyPrompts, safetyModel);
        }
    }

    @Test
    void disabledInputChecksKeepNormalizationAndTheOriginalHistory() {
        properties.setEnabled(false);
        var original = List.of(new ConversationTurn("안녕 😊 바보야", "이전 답변"));

        assertThat(code.prepareQuestion("안녕하세요 😊 로밍 알려줘")).isEqualTo("로밍 알려줘");
        assertThat(code.prepareHistory(original)).containsExactly(new ConversationTurn("바보야", "이전 답변"));
        assertThat(original).containsExactly(new ConversationTurn("안녕 😊 바보야", "이전 답변"));
        verifyNoInteractions(words, injection, safetyModel, planningLlm);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" \t", "안녕 😊"})
    void disabledSafetyStillRejectsAnEmptyPreparedQuestion(String question) {
        properties.setEnabled(false);

        assertThatThrownBy(() -> code.prepareQuestion(question))
                .isInstanceOfSatisfying(ChatException.class,
                        error -> assertThat(error.getErrorCode()).isEqualTo(ChatErrorCode.NO_USABLE_QUESTION));
        verifyNoInteractions(words, injection, safetyModel, planningLlm);
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void enabledCodeRejectionStopsBothTheIntegratedAiAndTheOrdinaryClassifier(boolean llmEnabled) {
        properties.setLlmEnabled(llmEnabled);
        doThrow(new ForbiddenWordException(ForbiddenWordErrorCode.FORBIDDEN_WORD_DETECTED))
                .when(words).validateForbiddenWord(any());

        assertThatThrownBy(() -> input.inspect(QUESTION, QUESTION, HISTORY)).isInstanceOf(ForbiddenWordException.class);

        verifyNoInteractions(safetyModel, safetyPrompts, planningPrompts, planningLlm);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "not json", "{\"sub_questions\":[]}"})
    void disabledSafetyFallsBackToTheOriginalOnClassifierParseFailure(String response) {
        properties.setLlmEnabled(false);
        when(planningPrompts.createPrompt(any(), any())).thenReturn(request);
        when(planningLlm.generateJudgment(request)).thenReturn(new LlmResponseDto(response));

        var result = input.inspectForPlanning(QUESTION, QUESTION, HISTORY);
        assertThat(result.subQuestions()).containsExactly(QUESTION);
        assertThat(result.planningFailed()).isTrue();
        verify(planningLlm, times(1)).generateJudgment(request);
        verifyNoInteractions(safetyModel, safetyPrompts);
    }

    @ParameterizedTest
    @CsvSource({"false,true", "true,false", "false,false"})
    void disabledSafetyRestoresOriginalSearchWithoutRetryingTheClassifier(boolean enabled, boolean llmEnabled) {
        properties.setEnabled(enabled);
        properties.setLlmEnabled(llmEnabled);
        when(turns.findPreviousTurns(1L, 3)).thenReturn(HISTORY);
        var route = new QuestionRouter.Route(List.of("split"), List.of("history"));
        when(router.route(QUESTION, true)).thenReturn(route);
        when(detector.isMultiTurn(QUESTION, HISTORY, List.of(QUESTION), route)).thenReturn(true);
        when(planningPrompts.createPrompt(any(), any())).thenReturn(request);
        for (var failure : List.of(LlmErrorCode.LLM_TIMEOUT, LlmErrorCode.LLM_BUSY, LlmErrorCode.LLM_RESPONSE_INVALID)) {
            doThrow(new LlmException(failure)).when(planningLlm).generateJudgment(request);
            var plan = planner(true).plan(attempt);
            assertThat(plan.searchQueries()).containsExactly(QUESTION);
            assertThat(plan.source()).isEqualTo(SubQuestionPlan.Source.LLM_FAILED);
            assertThat(plan.historyForAnswer()).containsExactlyElementsOf(HISTORY);
        }
        verify(planningLlm, times(3)).generateJudgment(request);
        verifyNoInteractions(safetyModel, safetyPrompts, queryGuard);
    }

    @ParameterizedTest
    @CsvSource({"true,true", "true,false", "false,true", "false,false"})
    void historyDatabaseFailureIsNeverReplacedByOriginalSearch(boolean enabled, boolean llmEnabled) {
        properties.setEnabled(enabled);
        properties.setLlmEnabled(llmEnabled);
        when(turns.findPreviousTurns(1L, 3)).thenThrow(new org.springframework.dao.QueryTimeoutException("mock DB failure"));
        assertThatThrownBy(() -> planner(true).plan(attempt))
                .isInstanceOfSatisfying(ChatException.class,
                        error -> assertThat(error.getErrorCode()).isEqualTo(ChatErrorCode.STORAGE_UNAVAILABLE));
        verifyNoInteractions(safetyModel, safetyPrompts, planningLlm, planningPrompts, router);
    }

    @Test
    void cancelledClassificationDoesNotBecomeAFallbackPlan() {
        properties.setLlmEnabled(false);
        when(planningPrompts.createPrompt(any(), any())).thenReturn(request);
        when(planningLlm.generateJudgment(request)).thenThrow(new java.util.concurrent.CancellationException());
        assertThatThrownBy(() -> input.inspectForPlanning(QUESTION, QUESTION, HISTORY))
                .isInstanceOf(java.util.concurrent.CancellationException.class);
        verifyNoInteractions(safetyModel, safetyPrompts);
    }

    @Test
    void disabledAiSafetyReusesTheExistingClassifierPromptAndSchema() {
        properties.setLlmEnabled(false);
        var originalPrompts = new SubQuestionPromptService(new ClassPathResource("prompts/sub-question-system.txt"),
                new ClassPathResource("prompts/sub-question-user.txt"));
        var service = new AiInputGuardService(safetyModel, safetyPrompts, code, properties, originalPrompts, planningLlm, org.mockito.Mockito.mock(com.ubot.guardreview.service.ReviewCheckService.class));
        when(planningLlm.generateJudgment(any())).thenReturn(new LlmResponseDto(PLANNED));

        assertThat(service.inspect(QUESTION, QUESTION, HISTORY)).containsExactlyElementsOf(QUERIES);

        var captured = ArgumentCaptor.forClass(LlmRequestDto.class);
        verify(planningLlm).generateJudgment(captured.capture());
        assertThat(captured.getValue().responseSchema()).isEqualTo(SubQuestionFormat.schema());
        assertThat(captured.getValue().messages().get(1).content()).contains(QUESTION, HISTORY.getFirst().question());
        verifyNoInteractions(safetyModel, safetyPrompts);
    }

    @Test
    void disabledSafetyUsesOnlyTheExistingClassifierForConcurrentPlanningRequests() throws Exception {
        properties.setEnabled(false);
        preparePlanning();
        var tasks = new ArrayList<Callable<List<String>>>();
        for (int i = 0; i < 32; i++) tasks.add(() -> input.inspect(QUESTION, QUESTION, HISTORY));

        try (var executor = Executors.newFixedThreadPool(8)) {
            for (var result : executor.invokeAll(tasks, 10, TimeUnit.SECONDS)) {
                assertThat(result.isCancelled()).isFalse();
                assertThat(result.get()).containsExactlyElementsOf(QUERIES);
            }
        }

        verify(planningLlm, times(32)).generateJudgment(request);
        verifyNoInteractions(words, injection, safetyModel, safetyPrompts);
    }

    @ParameterizedTest
    @CsvSource({"true,true", "true,false", "false,true", "false,false"})
    void switchesDoNotAddAiCallsToTheExistingSearchFirstRoute(boolean enabled, boolean llmEnabled) {
        properties.setEnabled(enabled);
        properties.setLlmEnabled(llmEnabled);
        when(router.route(any(), anyBoolean())).thenReturn(QuestionRouter.Route.none());

        var plan = planner(true).plan(attempt);

        assertThat(plan.searchFirst()).isTrue();
        assertThat(plan.searchQueries()).containsExactly(QUESTION);
        verifyNoInteractions(safetyModel, safetyPrompts, planningPrompts, planningLlm, queryGuard, detector);
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void disabledAiSafetyKeepsRouterQueryProtectionAndMultiTurnHandling(boolean codeEnabled) {
        properties.setEnabled(codeEnabled);
        properties.setLlmEnabled(false);
        var route = new QuestionRouter.Route(List.of("split"), List.of("history"));
        when(turns.findPreviousTurns(1L, 3)).thenReturn(HISTORY);
        when(router.route(QUESTION, true)).thenReturn(route);
        preparePlanning();
        prepareQueryProtection();
        when(detector.isMultiTurn(QUESTION, HISTORY, QUERIES, route)).thenReturn(true);

        var plan = planner(true).plan(attempt);

        assertThat(plan.searchQueries()).containsExactlyElementsOf(QUERIES);
        assertThat(plan.historyForAnswer()).containsExactlyElementsOf(HISTORY);
        assertThat(plan.multiTurn()).isTrue();
        var order = inOrder(router, planningLlm, queryGuard, detector);
        order.verify(router).route(QUESTION, true);
        order.verify(planningLlm).generateJudgment(request);
        order.verify(queryGuard).check(QUESTION, HISTORY, QUERIES);
        order.verify(detector).isMultiTurn(QUESTION, HISTORY, QUERIES, route);
        verifyNoInteractions(safetyModel, safetyPrompts);
    }

    @Test
    void searchFailureStillUsesTheExistingDelayedClassificationCondition() {
        properties.setLlmEnabled(false);
        when(turns.findPreviousTurns(1L, 3)).thenReturn(HISTORY);
        when(router.route(any(), anyBoolean())).thenReturn(QuestionRouter.Route.none());
        preparePlanning();
        prepareQueryProtection();
        var planner = planner(true);
        var first = planner.plan(attempt);
        assertThat(first.searchFirst()).isTrue();
        verifyNoInteractions(planningLlm);

        assertThat(planner.judge(attempt, first).searchQueries()).containsExactlyElementsOf(QUERIES);

        verify(planningLlm, times(1)).generateJudgment(request);
        verifyNoInteractions(safetyModel, safetyPrompts);
    }

    @Test
    void disablingTheExistingPlannerStillSkipsEveryPlanningAndSafetyModelCall() {
        properties.setEnabled(false);
        var planner = planner(false);
        var first = planner.plan(attempt);

        assertThat(planner.judge(attempt, first).searchQueries()).containsExactly(QUESTION);

        verifyNoInteractions(router, queryGuard, detector, safetyModel, safetyPrompts, planningLlm, planningPrompts);
    }

    private void preparePlanning() {
        when(planningPrompts.createPrompt(QUESTION, HISTORY)).thenReturn(request);
        when(planningLlm.generateJudgment(request)).thenReturn(new LlmResponseDto(PLANNED));
    }

    private void prepareQueryProtection() {
        when(queryGuard.check(any(), any(), any())).thenReturn(new SubQuestionGuard.Result(
                QUERIES.stream().map(query -> new SubQuestionGuard.Item(query, QUESTION)).toList(), false,
                EnumSet.noneOf(SubQuestionGuard.Reason.class)));
    }

    private SubQuestionPlanner planner(boolean enabled) {
        return new SubQuestionPlanner(enabled, turns, input, queryGuard, router, detector, code);
    }
}
