package com.ubot.chat.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import com.ubot.ai.dto.AiAnswer;
import com.ubot.ai.service.AiService;
import com.ubot.chat.entity.AnswerAttemptsHistory;
import com.ubot.chat.job.entity.ChatJobStatus;
import com.ubot.chat.util.IpRegionResolver;
import com.ubot.faq.dto.response.FaqSearchResponseDto;
import com.ubot.faq.enums.Intent;
import com.ubot.faq.retrieval.FaqRetrievalService;
import com.ubot.faq.service.FaqVectorService;
import com.ubot.forbiddenword.service.ForbiddenWordFilterService;
import com.ubot.unanswered.enums.UnansweredReason;
import com.ubot.unanswered.dto.UnansweredCandidate;
import com.ubot.unanswered.enums.UnansweredSource;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.util.ReflectionTestUtils;

import com.ubot.chat.job.service.ChatSubmissionService.ChatSubmission;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.mockito.Mockito.verifyNoInteractions;

@DisplayName("채팅 미응답 질문 저장 연결 테스트")
class ChatServiceUnansweredTest {
	private final FaqVectorService vector = ChatTestFixtures.vectorService();
	private final AiService ai = mock(AiService.class);
	private final ChatAttemptsService attempts = mock(ChatAttemptsService.class);
	private final IpRegionResolver ipRegionResolver = mock(IpRegionResolver.class);
	private final AnswerAttemptsHistory attempt = AnswerAttemptsHistory.builder()
			.id(1L).userId(1L).question("질문").idempotencyKey("a".repeat(64))
			.attemptCount(1).status("PENDING").build();
	private ChatJobTestHarness harness;
	private ChatService service;

	@BeforeEach
	void setUp() {
		ChatAnswerProcessor processor = ChatTestFixtures.processor(FaqRetrievalService.denseOnly(vector), ai, ChatTestFixtures.collector(), ChatTestFixtures.originalSubQuestions(), ChatTestFixtures.disabledCache(), ChatTestFixtures.passthroughSentenceFilter(), ChatTestFixtures.inputGuard());
		ReflectionTestUtils.setField(processor, "topK", 3);
		ReflectionTestUtils.setField(processor, "confidenceThreshold", 0.75);
		harness = new ChatJobTestHarness(attempts, processor).knowAttempts(attempt);
		service = new ChatService(attempts, ChatTestFixtures.inputGuard(),
				harness.submissionService, ipRegionResolver);
		when(attempts.createAnswerAttempt(1L, null, "질문")).thenReturn(attempt);
		when(attempts.recordFailure(any(), any())).thenAnswer(call -> {
			AnswerAttemptsHistory saved = call.getArgument(0);
			saved.fail(call.getArgument(1));
			return true;
		});
	}

	@Test
	@DisplayName("검색 결과가 없으면 NO_FAQ로 미응답 질문을 저장한다")
	void recordsNoFaq() {
		when(vector.getSimilarList("질문", 3)).thenReturn(List.of());

		service.createChat(1L, null, "질문", null, null);
		harness.runQueuedJobs();

		verify(harness.unanswered).enqueueUnansweredQuestion(1L, candidate(UnansweredReason.NO_FAQ, null, null));
	}

	@Test
	@DisplayName("유사도가 기준보다 낮으면 가장 가까운 FAQ와 함께 INSUFFICIENT_FAQ로 저장한다")
	void recordsInsufficientFaqWithBestResult() {
		when(vector.getSimilarList("질문", 3)).thenReturn(List.of(
				new FaqSearchResponseDto(7L, "q1", "a1", 0.6, Intent.GENERAL),
				new FaqSearchResponseDto(8L, "q2", "a2", 0.5, Intent.GENERAL)));

		service.createChat(1L, null, "질문", null, null);
		harness.runQueuedJobs();

		verify(harness.unanswered).enqueueUnansweredQuestion(1L, candidate(UnansweredReason.INSUFFICIENT_FAQ, 7L, 0.6));
	}

	@Test
	@DisplayName("미응답 대기열 등록이 실패하면 작업을 확정하지 않고 다시 처리할 수 있게 남긴다")
	void leavesJobUnfinishedWhenEnqueueFails() {
		when(vector.getSimilarList("질문", 3)).thenReturn(List.of());
		doThrow(new RuntimeException("db down")).when(harness.unanswered).enqueueUnansweredQuestion(any(), any());

		service.createChat(1L, null, "질문", null, null);
		harness.runQueuedJobs();

		assertThat(harness.jobs.getFirst().getStatus()).isEqualTo(ChatJobStatus.RUNNING);
		assertThat(harness.acked).isEmpty();
	}

	@Test
	@DisplayName("답변에 성공하면 미응답 질문을 저장하지 않는다")
	void doesNotRecordOnSuccess() {
		List<FaqSearchResponseDto> sources = List.of(new FaqSearchResponseDto(1L, "q", "a", 0.9, Intent.GENERAL));
		when(vector.getSimilarList("질문", 3)).thenReturn(sources);
		when(ai.generateAnswer(ChatTestFixtures.materialsFor("질문", sources))).thenReturn(new AiAnswer("답변", null, false));
		when(attempts.recordSuccess(any(), any(), any(), isNull())).thenAnswer(call -> {
			AnswerAttemptsHistory saved = call.getArgument(0);
			saved.succeed();
			return harness.logAnswer(call.getArgument(1));
		});

		service.createChat(1L, null, "질문", null, null);
		harness.runQueuedJobs();

		verify(harness.unanswered, never()).enqueueUnansweredQuestion(any(), any());
		assertThat(harness.jobs.getFirst().getStatus()).isEqualTo(ChatJobStatus.SUCCEEDED);
	}

	@Test
	@DisplayName("FAQ 부족 후보 없이 ABSTAIN만 반환되면 미응답으로 저장하지 않고 답변은 그대로 보낸다")
	void doesNotRecordAbstainWithoutFaqGapCandidate() {
		answerWithStatus("ABSTAIN");

		verify(harness.unanswered, never()).enqueueUnansweredQuestion(any(), any());
		assertThat(harness.jobs.getFirst().getStatus()).isEqualTo(ChatJobStatus.SUCCEEDED);
	}

	@ParameterizedTest
	@ValueSource(strings = {"ANSWER", "PARTIAL", "OUT_OF_SCOPE", "CONFLICT"})
	@DisplayName("ABSTAIN이 아닌 상태는 미응답으로 저장하지 않는다")
	void doesNotRecordOtherStatuses(String status) {
		answerWithStatus(status);

		verify(harness.unanswered, never()).enqueueUnansweredQuestion(any(), any());
	}

	private void answerWithStatus(String status) {
		List<FaqSearchResponseDto> sources = List.of(
				new FaqSearchResponseDto(1L, "q", "a", 0.9, Intent.GENERAL),
				new FaqSearchResponseDto(2L, "q2", "a2", 0.8, Intent.GENERAL));
		when(vector.getSimilarList("질문", 3)).thenReturn(sources);
		when(ai.generateAnswer(ChatTestFixtures.materialsFor("질문", sources)))
				.thenReturn(new AiAnswer("자료에 없어 답변할 수 없습니다.", null, false, status));
		when(attempts.recordSuccess(any(), any(), any(), isNull())).thenAnswer(call -> {
			AnswerAttemptsHistory saved = call.getArgument(0);
			saved.succeed();
			return harness.logAnswer(call.getArgument(1));
		});

		service.createChat(1L, null, "질문", null, null);
		harness.runQueuedJobs();
	}

	private static UnansweredCandidate candidate(UnansweredReason reason, Long bestFaqId, Double bestSimilarity) {
		return new UnansweredCandidate(1, "질문", "질문", reason, bestFaqId, bestSimilarity, UnansweredSource.RETRIEVAL);
	}
}
