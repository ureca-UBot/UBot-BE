package com.ubot.chat.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.ubot.ai.dto.ConversationTurn;
import com.ubot.ai.progress.AnswerProgress;
import com.ubot.ai.progress.AnswerProgress.Phase;
import com.ubot.chat.entity.AnswerAttemptsHistory;
import com.ubot.chat.repository.ConversationTurnRepository;
import com.ubot.faq.enums.Intent;
import com.ubot.llm.client.LlmClient;
import com.ubot.llm.dto.request.LlmMessageRequestDto;
import com.ubot.llm.dto.request.LlmRequestDto;
import com.ubot.llm.dto.response.LlmResponseDto;
import com.ubot.llm.enums.LlmMessageRole;
import com.ubot.llm.exception.LlmErrorCode;
import com.ubot.llm.exception.LlmException;
import com.ubot.llm.service.LlmService;
import com.ubot.prompt.service.SubQuestionPromptService;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.dao.QueryTimeoutException;

@DisplayName("하위 질문 정리 테스트")
class SubQuestionPlannerTest {
	private final ConversationTurnRepository turns = mock(ConversationTurnRepository.class);
	private final SubQuestionPromptService prompts = mock(SubQuestionPromptService.class);
	private final LlmService llm = mock(LlmService.class);
	private final SubQuestionGuard guard = mock(SubQuestionGuard.class);
	private final QuestionRouter router = mock(QuestionRouter.class);
	private final MultiTurnDetector detector = mock(MultiTurnDetector.class);
	private final LlmRequestDto request = new LlmRequestDto(List.of());
	private final QuestionRouter.Route judgeRoute = new QuestionRouter.Route(List.of("접속조사"), List.of());

	@BeforeEach
	void passGuard() {
		when(guard.check(any(), any(), any())).thenAnswer(invocation -> result(invocation.getArgument(2), false,
				EnumSet.noneOf(SubQuestionGuard.Reason.class)));
		when(router.route(any(), anyBoolean())).thenReturn(judgeRoute);
	}

	private SubQuestionPlanner planner(boolean enabled) {
		return new SubQuestionPlanner(enabled, turns, ChatTestFixtures.inputAiGuard(prompts, llm), guard, router, detector, ChatTestFixtures.passthroughInputGuard());
	}

	private static SubQuestionGuard.Result result(List<String> subQuestions, boolean fallback,
			EnumSet<SubQuestionGuard.Reason> reasons) {
		return new SubQuestionGuard.Result(subQuestions.stream()
				.map(subQuestion -> new SubQuestionGuard.Item(subQuestion, subQuestion)).toList(), fallback, reasons);
	}

	@Test
	@DisplayName("판정 LLM이 정리한 하위 질문을 쓰고, 같은 대화의 이전 대화를 최대 3개 넘기며, 멀티턴이면 답변에도 이전 대화를 넘긴다")
	void usesLlmOutputWithHistory() {
		var history = List.of(new ConversationTurn("5G 프리미엄 요금제", "월 89,000원입니다."));
		when(turns.findPreviousTurns(10L, 3)).thenReturn(history);
		when(prompts.createPrompt("그럼 데이터는?", history)).thenReturn(request);
		when(llm.generateJudgment(request)).thenReturn(new LlmResponseDto(
				"{\"sub_questions\": [\"5G 프리미엄 요금제 데이터 제공량\"]}"));
		when(detector.isMultiTurn("그럼 데이터는?", history, List.of("5G 프리미엄 요금제 데이터 제공량"), judgeRoute))
				.thenReturn(true);

		SubQuestionPlan plan = planner(true).plan(attempt(10L, 7L, "그럼 데이터는?"));

		assertThat(plan.searchQueries()).containsExactly("5G 프리미엄 요금제 데이터 제공량");
		assertThat(plan.source()).isEqualTo(SubQuestionPlan.Source.LLM);
		assertThat(plan.historyForAnswer()).isEqualTo(history);
	}

	@Test
	@DisplayName("멀티턴이 아니면 이전 대화가 있어도 답변에는 넘기지 않는다")
	void keepsHistoryFromAnswerWhenNotMultiTurn() {
		var history = List.of(new ConversationTurn("유심 재발급 방법", "매장에서 가능합니다."));
		when(turns.findPreviousTurns(10L, 3)).thenReturn(history);
		when(prompts.createPrompt(any(), any())).thenReturn(request);
		when(llm.generateJudgment(request)).thenReturn(new LlmResponseDto(
				"{\"sub_questions\": [\"해외 로밍 신청 방법\", \"로밍 요금\"]}"));

		SubQuestionPlan plan = planner(true).plan(attempt(10L, 7L, "해외 로밍 신청이랑 요금 알려줘"));

		assertThat(plan.history()).isEqualTo(history);
		assertThat(plan.multiTurn()).isFalse();
		assertThat(plan.historyForAnswer()).isEmpty();
	}

	@Test
	@DisplayName("판정할 표지가 없으면 판정 LLM 없이 원문으로 먼저 검색하는 계획을 내고, 실패하면 같은 이전 대화로 판정한다")
	void searchesOriginalFirstWithoutMarks() {
		var history = List.of(new ConversationTurn("유심 재발급 방법", "매장에서 가능합니다."));
		var none = QuestionRouter.Route.none();
		when(turns.findPreviousTurns(10L, 3)).thenReturn(history);
		when(router.route("해외 로밍은 어떻게 신청하나요?", true)).thenReturn(none);
		when(prompts.createPrompt("해외 로밍은 어떻게 신청하나요?", history)).thenReturn(request);
		when(llm.generateJudgment(request)).thenReturn(new LlmResponseDto("{\"sub_questions\": [\"해외 로밍 신청 방법\"]}"));
		AnswerAttemptsHistory attempt = attempt(10L, 7L, "해외 로밍은 어떻게 신청하나요?");

		SubQuestionPlan first = planner(true).plan(attempt, attempt.getQuestion());

		assertThat(first.searchFirst()).isTrue();
		assertThat(first.source()).isEqualTo(SubQuestionPlan.Source.ORIGINAL_SEARCH_FIRST);
		assertThat(first.searchQueries()).containsExactly("해외 로밍은 어떻게 신청하나요?");
		assertThat(first.history()).isEqualTo(history);
		verifyNoInteractions(llm);

		SubQuestionPlan judged = planner(true).judge(attempt, first);

		assertThat(judged.searchFirst()).isFalse();
		assertThat(judged.searchQueries()).containsExactly("해외 로밍 신청 방법");
		verify(prompts).createPrompt("해외 로밍은 어떻게 신청하나요?", history);
	}

	@Test
	@DisplayName("가드가 고친 하위 질문을 쓰고, 가드가 모두 버려 원문으로 돌리면 원문 하나로 진행한다")
	void appliesGuardResult() {
		var history = List.of(new ConversationTurn("매장 예약 방법", "앱에서 예약할 수 있습니다."));
		when(turns.findPreviousTurns(10L, 3)).thenReturn(history);
		when(prompts.createPrompt(any(), any())).thenReturn(request);
		when(llm.generateJudgment(request)).thenReturn(new LlmResponseDto(
				"{\"sub_questions\": [\"제주도 연동 매장 예약 가능吗?\"]}"));
		when(guard.check("제주도 연동 매장 예약 가능해요?", history, List.of("제주도 연동 매장 예약 가능吗?")))
				.thenReturn(result(List.of("제주도 연동 매장 예약 가능?"), false,
						EnumSet.of(SubQuestionGuard.Reason.FOREIGN_SCRIPT_REMOVED)));
		when(guard.check("며칠부터 정지돼요?", history, List.of("제주도 연동 매장 예약 가능吗?")))
				.thenReturn(result(List.of("며칠부터 정지돼요?"), true,
						EnumSet.of(SubQuestionGuard.Reason.COMMAND_DROPPED, SubQuestionGuard.Reason.EMPTY)));

		SubQuestionPlan modified = planner(true).plan(attempt(10L, 7L, "제주도 연동 매장 예약 가능해요?"));
		SubQuestionPlan fallback = planner(true).plan(attempt(10L, 7L, "며칠부터 정지돼요?"));

		assertThat(modified.searchQueries()).containsExactly("제주도 연동 매장 예약 가능?");
		assertThat(modified.source()).isEqualTo(SubQuestionPlan.Source.LLM);
		assertThat(fallback.searchQueries()).containsExactly("며칠부터 정지돼요?");
		assertThat(fallback.source()).isEqualTo(SubQuestionPlan.Source.GUARD_FALLBACK);
	}

	@Test
	@DisplayName("대화에 속하지 않은 질문은 이전 대화 없이 판정한다")
	void judgesWithoutHistoryOutsideConversation() {
		when(prompts.createPrompt("유심 재발급", List.of())).thenReturn(request);
		when(llm.generateJudgment(request)).thenReturn(new LlmResponseDto(
				"{\"sub_questions\": [\"유심 재발급\"]}"));

		SubQuestionPlan plan = planner(true).plan(attempt(10L, null, "유심 재발급"));

		assertThat(plan.searchQueries()).containsExactly("유심 재발급");
		verifyNoInteractions(turns);
	}

	@Test
	@DisplayName("질문이 4개 이상이어도 그대로 돌려주고 너무 많다고 표시한다")
	void marksTooManySubQuestions() {
		when(prompts.createPrompt(any(), any())).thenReturn(request);
		when(llm.generateJudgment(request)).thenReturn(new LlmResponseDto(
				"{\"sub_questions\": [\"a\", \"b\", \"c\", \"d\"]}"));

		SubQuestionPlan plan = planner(true).plan(attempt(10L, null, "a b c d"));

		assertThat(plan.searchQueries()).hasSize(4);
		assertThat(plan.isTooMany()).isTrue();
	}

	@ParameterizedTest
	@ValueSource(strings = {"timeout", "invalid", "db"})
	@DisplayName("AI 판정이나 대화 조회 실패를 원문 검색으로 대체하지 않는다")
	void fallsBackToOriginalOnFailure(String failure) {
		when(prompts.createPrompt(any(), any())).thenReturn(request);
		switch (failure) {
			case "timeout" -> when(llm.generateJudgment(request)).thenThrow(new LlmException(LlmErrorCode.LLM_TIMEOUT));
			case "invalid" -> when(llm.generateJudgment(request)).thenReturn(new LlmResponseDto("정리할 수 없습니다."));
			default -> when(turns.findPreviousTurns(anyLong(), anyInt())).thenThrow(new QueryTimeoutException("db"));
		}

		com.ubot.common.ErrorCode expected = switch (failure) {
			case "timeout" -> LlmErrorCode.LLM_TIMEOUT;
			case "invalid" -> LlmErrorCode.LLM_RESPONSE_INVALID;
			default -> com.ubot.chat.exception.ChatErrorCode.STORAGE_UNAVAILABLE;
		};
		org.assertj.core.api.Assertions.assertThatThrownBy(() -> planner(true).plan(attempt(10L, 7L, "유심 재발급")))
				.isInstanceOfSatisfying(com.ubot.common.GlobalException.class,
					error -> assertThat(error.getErrorCode()).isEqualTo(expected));
	}

	@Test
	@DisplayName("판정을 끄면 판정 LLM과 이전 대화를 쓰지 않고 원문 하나로 먼저 검색하며, 원문 검색이 실패해도 판정하지 않는다")
	void skipsJudgeWhenDisabled() {
		var attempt = attempt(10L, 7L, "유심 재발급");
		SubQuestionPlan plan = planner(false).plan(attempt, attempt.getQuestion());

		assertThat(plan.searchQueries()).containsExactly("유심 재발급");
		// 원문 먼저 검색 경로를 타야 FAQ 시맨틱 캐시를 봅니다.
		assertThat(plan.searchFirst()).isTrue();
		SubQuestionPlan judged = planner(false).judge(attempt, plan);
		assertThat(judged.searchQueries()).containsExactly("유심 재발급");
		assertThat(judged.source()).isEqualTo(SubQuestionPlan.Source.ORIGINAL);
		verifyNoInteractions(turns, prompts, llm, guard);
	}

	@Test
	@DisplayName("재검색도 표지가 있으면 판정 LLM을 부르고, 이전 대화는 원본 시도를 물을 때 기준으로 읽는다")
	void researchJudgesWithSourceHistory() {
		var history = List.of(new ConversationTurn("5G 프리미엄 요금제", "월 89,000원입니다."));
		when(turns.findPreviousTurns(5L, 3)).thenReturn(history);
		when(prompts.createPrompt("그럼 데이터는?", history)).thenReturn(request);
		when(llm.generateJudgment(request)).thenReturn(new LlmResponseDto(
				"{\"sub_questions\": [\"5G 프리미엄 요금제 데이터 제공량\"]}"));

		SubQuestionPlan plan = planner(true).plan(research(5L, 7L, "그럼 데이터는?"));

		assertThat(plan.searchQueries()).containsExactly("5G 프리미엄 요금제 데이터 제공량");
		assertThat(plan.source()).isEqualTo(SubQuestionPlan.Source.LLM);
		assertThat(plan.history()).isEqualTo(history);
		// 재검색 시도(20) 기준으로 읽으면 원본 질문이 직전 대화로 들어가므로 원본 시도(5) 기준으로만 읽습니다.
		verify(turns).findPreviousTurns(5L, 3);
		verify(turns, never()).findPreviousTurns(20L, 3);
	}

	@Test
	@DisplayName("재검색도 표지가 없으면 판정 LLM 없이 원문으로 먼저 검색한다")
	void researchSearchesOriginalFirstWithoutMarks() {
		when(turns.findPreviousTurns(5L, 3)).thenReturn(List.of());
		when(router.route("유심 재발급 방법", false)).thenReturn(QuestionRouter.Route.none());

		SubQuestionPlan plan = planner(true).plan(research(5L, 7L, "유심 재발급 방법"));

		assertThat(plan.searchFirst()).isTrue();
		assertThat(plan.searchQueries()).containsExactly("유심 재발급 방법");
		verifyNoInteractions(llm);
	}

	@ParameterizedTest
	@ValueSource(booleans = {true, false})
	@DisplayName("선판정과 원문 검색 후 판정 모두 답변 생성 단계를 앞당기지 않는다")
	void keepsPhasesMovingForwardAcrossJudgment(boolean judgeInitially) {
		var client = mock(LlmClient.class);
		var realLlm = new LlmService(client, 1);
		var realPlanner = new SubQuestionPlanner(true, turns, ChatTestFixtures.inputAiGuard(prompts, realLlm), guard, router, detector, ChatTestFixtures.passthroughInputGuard());
		var attempt = attempt(10L, null, "로밍 신청과 요금 알려줘");
		var judgmentRequest = new LlmRequestDto(List.of(
				new LlmMessageRequestDto(LlmMessageRole.USER, "하위 질문을 정리하세요.")));
		var answerRequest = new LlmRequestDto(List.of(
				new LlmMessageRequestDto(LlmMessageRole.USER, "자료를 바탕으로 답변하세요.")));
		when(prompts.createPrompt(attempt.getQuestion(), List.of())).thenReturn(judgmentRequest);
		when(client.generateAnswer(judgmentRequest)).thenReturn(new LlmResponseDto(
				"{\"sub_questions\": [\"로밍 신청 방법\", \"로밍 요금\"]}"));
		when(client.generateAnswer(answerRequest)).thenReturn(new LlmResponseDto("로밍 신청과 요금 안내입니다."));
		if (!judgeInitially) {
			when(router.route(any(), anyBoolean())).thenReturn(QuestionRouter.Route.none());
		}
		var phases = new ArrayList<Phase>();

		LlmResponseDto answer = AnswerProgress.observe(phases::add, () -> {
			AnswerProgress.report(Phase.SEARCHING);
			SubQuestionPlan plan = realPlanner.plan(attempt, attempt.getQuestion());
			if (plan.searchFirst()) {
				plan = realPlanner.judge(attempt, plan);
			}
			assertThat(plan.source()).isEqualTo(SubQuestionPlan.Source.LLM);
			assertThat(plan.searchQueries()).containsExactly("로밍 신청 방법", "로밍 요금");
			assertThat(phases).containsExactly(Phase.SEARCHING);
			AnswerProgress.report(Phase.COLLECTING_CONTEXT);
			return realLlm.generateAnswer(answerRequest);
		});

		assertThat(answer.answer()).isEqualTo("로밍 신청과 요금 안내입니다.");
		assertThat(phases).containsExactly(Phase.SEARCHING, Phase.COLLECTING_CONTEXT,
				Phase.WAITING_FOR_MODEL, Phase.GENERATING);
	}

	private AnswerAttemptsHistory attempt(Long id, Long conversationId, String question) {
		return AnswerAttemptsHistory.builder().id(id).userId(1L).conversationId(conversationId).question(question)
				.status("PENDING").build();
	}

	private AnswerAttemptsHistory research(Long sourceAttemptId, Long conversationId, String question) {
		return AnswerAttemptsHistory.builder().id(20L).userId(1L).conversationId(conversationId).question(question)
				.status("PENDING").sourceAttemptId(sourceAttemptId).intent(Intent.GENERAL).build();
	}
}
