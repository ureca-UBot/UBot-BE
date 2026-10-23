package com.ubot.chat.service;

import static com.ubot.ai.service.GuardPipelineFixtures.NOTICE;
import static com.ubot.ai.service.GuardPipelineFixtures.QUESTION;
import static com.ubot.ai.service.GuardPipelineFixtures.input;
import static com.ubot.ai.service.GuardPipelineFixtures.missingMaterials;
import static com.ubot.ai.service.GuardPipelineFixtures.output;
import static com.ubot.ai.service.GuardPipelineFixtures.unrelatedFaq;
import static com.ubot.ai.service.GuardPipelineFixtures.vector;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.ubot.ai.dto.AiAnswer;
import com.ubot.ai.dto.OutputGuardResult;
import com.ubot.ai.service.AiOutputGuardService;
import com.ubot.ai.service.AiService;
import com.ubot.ai.service.SubQuestionLabels;
import com.ubot.ai.tool.AiToolRegistry;
import com.ubot.chat.entity.AnswerAttemptsHistory;
import com.ubot.chat.exception.ChatErrorCode;
import com.ubot.embedding.service.EmbeddingProfileService;
import com.ubot.faq.retrieval.DenseFaqCandidates;
import com.ubot.faq.retrieval.FaqRetrievalService;
import com.ubot.faq.retrieval.RetrievalOutcome;
import com.ubot.llm.dto.request.LlmRequestDto;
import com.ubot.llm.dto.response.LlmResponseDto;
import com.ubot.llm.exception.LlmErrorCode;
import com.ubot.llm.exception.LlmException;
import com.ubot.llm.service.LlmService;
import com.ubot.prompt.service.PromptService;
import com.ubot.unanswered.dto.UnansweredCandidate;
import com.ubot.unanswered.enums.UnansweredReason;
import com.ubot.unanswered.enums.UnansweredSource;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.util.ReflectionTestUtils;

/** 출력 가드 기반 수집 제거와 기존 모델 미응답 저장을 외부 연결 없이 확인합니다. */
class OutputGuardUnansweredConnectionTest {
	private final LlmService llm = mock(LlmService.class);
	private final PromptService prompts = mock(PromptService.class);
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
		ai = new AiService(prompts, llm, mock(AiToolRegistry.class), guard, SubQuestionLabels.withoutMorphology());
		processor = processorFor(ai);
		when(planner.plan(attempt, QUESTION)).thenReturn(SubQuestionPlan.original(QUESTION, SubQuestionPlan.Source.ORIGINAL));
		when(retrieval.searchCandidates(QUESTION, null, 3, 0))
				.thenReturn(new DenseFaqCandidates(List.of(unrelatedFaq()), 0, vector()));
		when(retrieval.rankCandidates(eq(QUESTION), any(), eq(3)))
				.thenReturn(new RetrievalOutcome(List.of(unrelatedFaq()), unrelatedFaq(), false, null));
		when(profiles.getCurrentProfileId()).thenReturn(1L);
		when(prompts.createSlotPrompt(any(), any(), any(), any(), any())).thenReturn(new LlmRequestDto(List.of()));
		when(llm.generateAnswer(any())).thenReturn(new LlmResponseDto(NOTICE));
	}

	private ChatAnswerProcessor processorFor(AiService service) {
		var processor = new ChatAnswerProcessor(retrieval, service, ChatTestFixtures.collector(), planner,
				ChatTestFixtures.disabledCache(), ChatTestFixtures.passthroughSentenceFilter(), input(), profiles);
		ReflectionTestUtils.setField(processor, "topK", 3);
		ReflectionTestUtils.setField(processor, "confidenceThreshold", .75);
		return processor;
	}
	@Test void FAQ_부족_검사결과가_있어도_미응답_후보를_만들지_않는다() {
		var result = processor.generateAnswer(attempt, null, () -> {}, candidates);
		assertThat(result.isFailed()).isFalse();
		assertThat(result.answer().outputGuardResult().reasons())
				.contains(OutputGuardResult.Reason.FAQ_SHORTAGE_SUSPECTED);
		assertThat(candidates).isEmpty();
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
	}
	@Test void 차단은_기존_LLM006이고_미응답_후보는_아니다() {
		when(llm.generateAnswer(any())).thenReturn(new LlmResponseDto("고객은 바보입니다."));
		assertThatThrownBy(() -> processor.generateAnswer(attempt, null, () -> {}, candidates))
				.isInstanceOfSatisfying(LlmException.class, e -> assertThat(e.getErrorCode()).isEqualTo(LlmErrorCode.LLM_OUTPUT_REJECTED));
		assertThat(candidates).isEmpty();
	}
	@Test void 정상_답변과_기존_출력_검사는_유지한다() {
		var supported = new com.ubot.faq.dto.response.FaqSearchResponseDto(17L, "회선 정지 방법",
				"회선 정지는 앱에서 신청할 수 있습니다.", .91, com.ubot.faq.enums.Intent.GENERAL);
		when(retrieval.rankCandidates(eq(QUESTION), any(), eq(3)))
				.thenReturn(new RetrievalOutcome(List.of(supported), supported, false, null));
		when(llm.generateAnswer(any())).thenReturn(new LlmResponseDto(supported.answer()));
		var result = processor.generateAnswer(attempt, null, () -> {}, candidates);
		assertThat(result.isFailed()).isFalse();
		assertThat(result.answer().answer()).isEqualTo(supported.answer());
		assertThat(candidates).isEmpty();
	}
	@Test void 검색_미응답은_기존_출처와_재사용_벡터를_유지한다() {
		when(retrieval.rankCandidates(eq(QUESTION), any(), eq(3)))
				.thenReturn(new RetrievalOutcome(List.of(), null, false, null));

		assertThat(processor.generateAnswer(attempt, null, () -> {}, candidates).errorCode())
				.isEqualTo(ChatErrorCode.NO_FAQ);
		assertThat(candidates).singleElement().satisfies(candidate -> {
			assertThat(candidate.source()).isEqualTo(UnansweredSource.RETRIEVAL);
			assertThat(candidate.embeddingProfileId()).isEqualTo(1L);
			assertThat(candidate.queryEmbedding()).containsExactlyElementsOf(
					java.util.stream.IntStream.range(0, vector().toArray().length)
							.mapToObj(index -> index == 0 ? 1F : 0F).toList());
			assertThat(candidate.rejectedThreshold()).isNull();
		});
		verifyNoInteractions(llm);
	}

	@Test void FAQ_부족_검사결과는_Worker를_거쳐도_Outbox에_등록하지_않는다() {
		var harness = runJob(processor);

		verify(harness.unanswered, never()).enqueueUnansweredQuestion(any(), any());
		assertThat(harness.jobs.getFirst().getStatus())
				.isEqualTo(com.ubot.chat.job.entity.ChatJobStatus.SUCCEEDED);
		verify(llm, times(1)).generateAnswer(any());
	}

	@ParameterizedTest
	@ValueSource(strings = {"ABSTAIN", "CONFLICT"})
	void 모델_자체의_미응답은_Worker를_통해_기존_Outbox로_전달한다(String status) {
		when(llm.generateAnswer(any())).thenReturn(new LlmResponseDto(
				"{\"answers\":[{\"answer\":\"\",\"status\":\"" + status + "\"}],\"evidence_ids\":[\"17\"]}"));

		var harness = runJob(processor);

		var reason = "ABSTAIN".equals(status) ? UnansweredReason.LLM_ABSTAIN : UnansweredReason.LLM_CONFLICT;
		verify(harness.unanswered).enqueueUnansweredQuestion(eq(71L), argThat(candidate ->
				candidate.source() == UnansweredSource.LLM_MODEL && candidate.reason() == reason
						&& candidate.subQuestion().equals(QUESTION)));
		assertThat(harness.jobs.getFirst().getStatus())
				.isEqualTo(com.ubot.chat.job.entity.ChatJobStatus.SUCCEEDED);
		verify(llm, times(1)).generateAnswer(any());
	}

	@Test void 가드가_바꾼_ABSTAIN은_모델_미응답으로_재분류하거나_Outbox에_등록하지_않는다() {
		var guard = mock(AiOutputGuardService.class);
		when(guard.inspectAnswer(any(), any(), any(), any()))
				.thenReturn(new OutputGuardResult("", false, Set.of(OutputGuardResult.Reason.ANSWER_ERROR), true));
		var guardedAi = new AiService(prompts, llm, mock(AiToolRegistry.class), guard,
				SubQuestionLabels.withoutMorphology());
		var guardedProcessor = processorFor(guardedAi);
		when(llm.generateAnswer(any())).thenReturn(new LlmResponseDto(
				"{\"answers\":[{\"answer\":\"근거 없는 안내\",\"status\":\"ANSWER\"}],\"evidence_ids\":[\"17\"]}"));

		var result = guardedProcessor.generateAnswer(attempt, null, () -> {}, candidates);
		assertThat(result.answer().slotOutcomes()).containsExactly(new AiAnswer.SlotOutcome("ABSTAIN", true));
		assertThat(candidates).isEmpty();
		var harness = runJob(guardedProcessor);

		verify(harness.unanswered, never()).enqueueUnansweredQuestion(any(), any());
		assertThat(harness.jobs.getFirst().getStatus())
				.isEqualTo(com.ubot.chat.job.entity.ChatJobStatus.SUCCEEDED);
		verify(guard, times(2)).inspectAnswer(any(), any(), any(), any());
	}

	private ChatJobTestHarness runJob(ChatAnswerProcessor processor) {
		var attempts = mock(ChatAttemptsService.class);
		var harness = new ChatJobTestHarness(attempts, processor).knowAttempts(attempt);
		var service = new ChatService(attempts, input(), harness.submissionService,
				mock(com.ubot.chat.util.IpRegionResolver.class), ChatTestFixtures.noRateLimit());
		when(attempts.createAnswerAttempt(1L, null, QUESTION)).thenReturn(attempt);
		when(attempts.recordSuccess(any(), any(), any(), isNull())).thenAnswer(call -> {
			AnswerAttemptsHistory saved = call.getArgument(0);
			saved.succeed();
			return harness.logAnswer(call.getArgument(1));
		});

		service.createChat(1L, null, QUESTION, null, null);
		harness.runQueuedJobs();

		return harness;
	}

	@Test void 내부_검사결과는_사용자_JSON에_포함하지_않는다() {
		var answer = ai.generateAnswer(missingMaterials());
		String json = tools.jackson.databind.json.JsonMapper.builder().build().writeValueAsString(answer);
		assertThat(json).doesNotContain("outputGuardResult", "FAQ_SHORTAGE_SUSPECTED");
	}
}
