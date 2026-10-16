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
import com.ubot.chat.exception.ChatErrorCode;
import com.ubot.chat.job.entity.ChatJobStatus;
import com.ubot.chat.util.IpRegionResolver;
import com.ubot.faq.dto.response.FaqSearchResponseDto;
import com.ubot.faq.enums.Intent;
import com.ubot.faq.retrieval.FaqRetrievalService;
import com.ubot.faq.service.FaqVectorService;
import com.ubot.forbiddenword.service.ForbiddenWordFilterService;
import com.ubot.unanswered.enums.UnansweredReason;
import com.ubot.unanswered.service.UnansweredQuestionService;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

@DisplayName("채팅 미응답 질문 저장 연결 테스트")
class ChatServiceUnansweredTest {
	private final FaqVectorService vector = mock(FaqVectorService.class);
	private final AiService ai = mock(AiService.class);
	private final ChatAttemptsService attempts = mock(ChatAttemptsService.class);
	private final IpRegionResolver ipRegionResolver = mock(IpRegionResolver.class);
	private final UnansweredQuestionService unanswered = mock(UnansweredQuestionService.class);
	private final AnswerAttemptsHistory attempt = AnswerAttemptsHistory.builder()
			.id(1L).userId(1L).question("질문").idempotencyKey("a".repeat(64))
			.attemptCount(1).status("PENDING").build();
	private ChatJobTestHarness harness;
	private ChatService service;

	@BeforeEach
	void setUp() {
		ChatAnswerProcessor processor = new ChatAnswerProcessor(
				FaqRetrievalService.denseOnly(vector), ai, unanswered, ChatTestFixtures.collector(), ChatTestFixtures.disabledCache());
		ReflectionTestUtils.setField(processor, "topK", 3);
		ReflectionTestUtils.setField(processor, "confidenceThreshold", 0.75);
		harness = new ChatJobTestHarness(attempts, processor).knowAttempts(attempt);
		service = new ChatService(attempts, mock(ForbiddenWordFilterService.class),
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

		verify(unanswered).createUnansweredQuestion(1L, "질문", UnansweredReason.NO_FAQ, null, null);
	}

	@Test
	@DisplayName("유사도가 기준보다 낮으면 가장 가까운 FAQ와 함께 INSUFFICIENT_FAQ로 저장한다")
	void recordsInsufficientFaqWithBestResult() {
		when(vector.getSimilarList("질문", 3)).thenReturn(List.of(
				new FaqSearchResponseDto(7L, "q1", "a1", 0.6, Intent.GENERAL),
				new FaqSearchResponseDto(8L, "q2", "a2", 0.5, Intent.GENERAL)));

		service.createChat(1L, null, "질문", null, null);
		harness.runQueuedJobs();

		verify(unanswered).createUnansweredQuestion(1L, "질문", UnansweredReason.INSUFFICIENT_FAQ, 7L, 0.6);
	}

	@Test
	@DisplayName("미응답 저장이 실패해도 채팅 실패 기록은 그대로 남긴다")
	void keepsChatFailureWhenRecordFails() {
		when(vector.getSimilarList("질문", 3)).thenReturn(List.of());
		doThrow(new RuntimeException("embedding down")).when(unanswered).createUnansweredQuestion(any(), any(), any(), any(), any());

		service.createChat(1L, null, "질문", null, null);
		harness.runQueuedJobs();

		verify(attempts).recordFailure(attempt, ChatErrorCode.NO_FAQ);
		assertThat(harness.jobs.getFirst().getStatus()).isEqualTo(ChatJobStatus.FAILED);
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

		verify(unanswered, never()).createUnansweredQuestion(any(), any(), any(), any(), any());
		assertThat(harness.jobs.getFirst().getStatus()).isEqualTo(ChatJobStatus.SUCCEEDED);
	}
}
