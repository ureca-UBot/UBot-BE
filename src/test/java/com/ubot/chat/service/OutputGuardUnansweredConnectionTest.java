package com.ubot.chat.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static com.ubot.ai.service.GuardPipelineFixtures.*;
import com.ubot.ai.dto.*;
import com.ubot.ai.service.*;
import com.ubot.ai.tool.AiToolRegistry;
import com.ubot.chat.entity.AnswerAttemptsHistory;
import com.ubot.chat.exception.ChatErrorCode;
import com.ubot.embedding.service.EmbeddingProfileService;
import com.ubot.faq.retrieval.*;
import com.ubot.llm.dto.request.LlmRequestDto;
import com.ubot.llm.dto.response.LlmResponseDto;
import com.ubot.llm.exception.*;
import com.ubot.llm.service.LlmService;
import com.ubot.prompt.service.PromptService;
import com.ubot.unanswered.enums.*;
import com.ubot.unanswered.service.*;
import com.ubot.unanswered.service.RejectedQuestionDuplicateChecker.QuestionEmbedding;
import java.util.List;
import java.util.ArrayList;
import com.ubot.unanswered.dto.UnansweredCandidate;
import java.util.OptionalDouble;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

/** 실제 검사·선별·채팅 연결을 쓰고 모델·검색·저장·프로필만 모의 객체로 막습니다. */
class OutputGuardUnansweredConnectionTest {
    private final LlmService llm = mock(LlmService.class);
    private final PromptService prompts = mock(PromptService.class);
    private final UnansweredQuestionService unanswered = mock(UnansweredQuestionService.class);
    private final FaqRetrievalService retrieval = mock(FaqRetrievalService.class);
    private final SubQuestionPlanner planner = mock(SubQuestionPlanner.class);
    private final EmbeddingProfileService profiles = mock(EmbeddingProfileService.class);
    private final List<UnansweredCandidate> candidates = new ArrayList<>();
    private ChatAnswerProcessor processor;
    private AiService ai;
    private final AnswerAttemptsHistory attempt = AnswerAttemptsHistory.builder().id(71L).userId(1L)
            .question(QUESTION).attemptCount(1).status("PENDING").idempotencyKey("offline-only").build();

    @BeforeEach void setUp() {
        var guard = output();
        var input = input();
        ai = new AiService(prompts, llm, mock(AiToolRegistry.class), guard, SubQuestionLabels.withoutMorphology());
        processor = new ChatAnswerProcessor(retrieval, ai, ChatTestFixtures.collector(), planner, ChatTestFixtures.disabledCache(), ChatTestFixtures.passthroughSentenceFilter(), input, new FaqGapCandidateSelector(input, guard), profiles);
        ReflectionTestUtils.setField(processor, "topK", 3);
        ReflectionTestUtils.setField(processor, "confidenceThreshold", .75);
        when(planner.plan(attempt, QUESTION)).thenReturn(SubQuestionPlan.original(QUESTION, SubQuestionPlan.Source.ORIGINAL));
        when(retrieval.searchCandidates(QUESTION, null, 3, 0))
                .thenReturn(new DenseFaqCandidates(List.of(unrelatedFaq()), 0, vector()));
        when(retrieval.rankCandidates(eq(QUESTION), any(), eq(3)))
                .thenReturn(new RetrievalOutcome(List.of(unrelatedFaq()), unrelatedFaq(), false, null));
        when(profiles.getCurrentProfileId()).thenReturn(1L);
        when(prompts.createPrompt(any(), any(), any(), any(), any())).thenReturn(new LlmRequestDto(List.of()));
        when(llm.generateAnswer(any())).thenReturn(new LlmResponseDto(NOTICE));
    }
    @Test void 실제_검사와_선별_결과만_출력가드_출처로_저장에_전달한다() {
        var result = processor.generateAnswer(attempt, null, () -> {}, candidates);
        assertThat(result.isFailed()).isFalse();
        assertThat(candidates).singleElement().satisfies(candidate -> {
            assertThat(candidate.subQuestionIndex()).isEqualTo(1);
            assertThat(candidate.subQuestion()).isEqualTo(QUESTION);
            assertThat(candidate.userText()).isEqualTo(QUESTION);
            assertThat(candidate.reason()).isEqualTo(UnansweredReason.INSUFFICIENT_FAQ);
            assertThat(candidate.bestFaqId()).isEqualTo(17L);
            assertThat(candidate.bestSimilarity()).isEqualTo(.91);
            assertThat(candidate.source()).isEqualTo(UnansweredSource.OUTPUT_GUARD);
            assertThat(candidate.embeddingProfileId()).isEqualTo(1L);
            assertThat(candidate.queryEmbedding()).hasSize(vector().toArray().length);
            assertThat(candidate.rejectedThreshold()).isNull();
        });
        verifyNoInteractions(unanswered);
        assertThat(result.answer().answer()).isEqualTo(NOTICE);
        verify(llm, times(1)).generateAnswer(any()); // 모의 객체 호출 횟수이며 실제 모델은 호출하지 않습니다.
    }
    @ParameterizedTest @ValueSource(strings = {"ABSTAIN", "PARTIAL"})
    void 상태만으로는_후보를_저장하지_않는다(String status) {
        var supported = new com.ubot.faq.dto.response.FaqSearchResponseDto(17L, "회선 정지 방법",
                "회선 정지는 앱에서 신청할 수 있습니다.", .91, com.ubot.faq.enums.Intent.GENERAL);
        when(retrieval.rankCandidates(eq(QUESTION), any(), eq(3)))
                .thenReturn(new RetrievalOutcome(List.of(supported), supported, false, null));
        when(llm.generateAnswer(any())).thenReturn(new LlmResponseDto("{\"answer\":\"" + NOTICE
                + "\",\"status\":\"" + status + "\",\"evidence_ids\":[\"17\"]}"));
        assertThat(processor.generateAnswer(attempt, null, () -> {}, candidates).isFailed()).isFalse();
        assertThat(candidates).isEmpty();
        verifyNoInteractions(unanswered);
    }
    @Test void 차단은_기존_LLM006이고_미응답_후보는_아니다() {
        when(llm.generateAnswer(any())).thenReturn(new LlmResponseDto("고객은 바보입니다."));
        assertThatThrownBy(() -> processor.generateAnswer(attempt, null, () -> {}, candidates))
                .isInstanceOfSatisfying(LlmException.class, e -> assertThat(e.getErrorCode()).isEqualTo(LlmErrorCode.LLM_OUTPUT_REJECTED));
        assertThat(candidates).isEmpty();
        verifyNoInteractions(unanswered);
    }
    @Test void 후보_실제_저장은_답변_계산에서_호출하지_않는다() {
        var result = processor.generateAnswer(attempt, null, () -> {}, candidates);
        assertThat(result.isFailed()).isFalse();
        assertThat(result.answer().answer()).isEqualTo(NOTICE);
        assertThat(candidates).hasSize(1);
        verifyNoInteractions(unanswered);
    }
    @Test void 실제_사용중인_반려_자동생략_기준은_아직_미설정이다() {
        assertThat((OptionalDouble) ReflectionTestUtils.getField(ChatAnswerProcessor.class, "REJECTED_DUPLICATE_THRESHOLD")).isEmpty();
    }

    @Test void 출력_후보는_완료_서비스를_통해_Outbox_등록으로_전달한다() {
        var attempts = mock(ChatAttemptsService.class);
        var harness = new ChatJobTestHarness(attempts, processor).knowAttempts(attempt);
        var service = new ChatService(attempts, input(), harness.submissionService,
                mock(com.ubot.chat.util.IpRegionResolver.class));
        when(attempts.createAnswerAttempt(1L, null, QUESTION)).thenReturn(attempt);
        when(attempts.recordSuccess(any(), any(), any(), isNull())).thenAnswer(call -> {
            AnswerAttemptsHistory saved = call.getArgument(0);
            saved.succeed();
            return harness.logAnswer(call.getArgument(1));
        });

        service.createChat(1L, null, QUESTION, null, null);
        harness.runQueuedJobs();

        verify(harness.unanswered).enqueueUnansweredQuestion(eq(71L), argThat(candidate ->
                candidate.source() == UnansweredSource.OUTPUT_GUARD
                        && candidate.subQuestion().equals(QUESTION)
                        && candidate.embeddingProfileId().equals(1L)
                        && candidate.queryEmbedding().size() == vector().toArray().length));
        assertThat(harness.jobs.getFirst().getStatus())
                .isEqualTo(com.ubot.chat.job.entity.ChatJobStatus.SUCCEEDED);
        verifyNoInteractions(unanswered);
        verify(llm, times(1)).generateAnswer(any());
    }

    @Test void 내부_검사결과는_사용자_JSON에_포함하지_않는다() {
        var answer = ai.generateAnswer(missingMaterials());
        String json = tools.jackson.databind.json.JsonMapper.builder().build().writeValueAsString(answer);
        assertThat(json).doesNotContain("outputGuardResult", "FAQ_SHORTAGE_SUSPECTED");
    }
}
