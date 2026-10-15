package com.ubot.chat.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.ubot.ai.dto.ConversationTurn;
import com.ubot.chat.entity.AnswerAttemptsHistory;
import com.ubot.chat.entity.QuestionLog;
import com.ubot.chat.repository.ConversationTurnRepository;
import com.ubot.chat.repository.QuestionLogRepository;
import com.ubot.faq.enums.Intent;
import com.ubot.llm.dto.request.LlmRequestDto;
import com.ubot.llm.dto.response.LlmResponseDto;
import com.ubot.llm.exception.LlmErrorCode;
import com.ubot.llm.exception.LlmException;
import com.ubot.llm.service.LlmService;
import com.ubot.prompt.service.SubQuestionPromptService;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.dao.QueryTimeoutException;

@DisplayName("하위 질문 정리 테스트")
class SubQuestionPlannerTest {
	private final ConversationTurnRepository turns = mock(ConversationTurnRepository.class);
	private final QuestionLogRepository questionLogs = mock(QuestionLogRepository.class);
	private final SubQuestionPromptService prompts = mock(SubQuestionPromptService.class);
	private final LlmService llm = mock(LlmService.class);
	private final LlmRequestDto request = new LlmRequestDto(List.of());

	private SubQuestionPlanner planner(boolean enabled) {
		return new SubQuestionPlanner(enabled, turns, questionLogs, prompts, llm);
	}

	@Test
	@DisplayName("판정 LLM이 정리한 하위 질문을 쓰고, 같은 대화의 이전 대화를 최대 3개 넘긴다")
	void usesLlmOutputWithHistory() {
		var history = List.of(new ConversationTurn(List.of("5G 프리미엄 요금제"), "월 89,000원입니다."));
		when(turns.findPreviousTurns(10L, 3)).thenReturn(history);
		when(prompts.createPrompt("그럼 데이터는?", history)).thenReturn(request);
		when(llm.generateAnswer(request)).thenReturn(new LlmResponseDto(
				"{\"context_used\": true, \"sub_questions\": [\"5G 프리미엄 요금제 데이터 제공량\"]}"));

		SubQuestionPlan plan = planner(true).plan(attempt(10L, 7L, "그럼 데이터는?"));

		assertThat(plan.subQuestions()).containsExactly("5G 프리미엄 요금제 데이터 제공량");
		assertThat(plan.contextUsed()).isTrue();
		assertThat(plan.source()).isEqualTo(SubQuestionPlan.Source.LLM);
	}

	@Test
	@DisplayName("대화에 속하지 않은 질문은 이전 대화 없이 판정한다")
	void judgesWithoutHistoryOutsideConversation() {
		when(prompts.createPrompt("유심 재발급", List.of())).thenReturn(request);
		when(llm.generateAnswer(request)).thenReturn(new LlmResponseDto(
				"{\"context_used\": false, \"sub_questions\": [\"유심 재발급\"]}"));

		SubQuestionPlan plan = planner(true).plan(attempt(10L, null, "유심 재발급"));

		assertThat(plan.subQuestions()).containsExactly("유심 재발급");
		verifyNoInteractions(turns);
	}

	@Test
	@DisplayName("질문이 4개 이상이어도 그대로 돌려주고 너무 많다고 표시한다")
	void marksTooManySubQuestions() {
		when(prompts.createPrompt(any(), any())).thenReturn(request);
		when(llm.generateAnswer(request)).thenReturn(new LlmResponseDto(
				"{\"context_used\": false, \"sub_questions\": [\"a\", \"b\", \"c\", \"d\"]}"));

		SubQuestionPlan plan = planner(true).plan(attempt(10L, null, "a b c d"));

		assertThat(plan.subQuestions()).hasSize(4);
		assertThat(plan.isTooMany()).isTrue();
	}

	@ParameterizedTest
	@ValueSource(strings = {"timeout", "invalid", "db"})
	@DisplayName("판정 LLM 호출·응답 해석·이전 대화 조회에 실패하면 원문 하나로 진행한다")
	void fallsBackToOriginalOnFailure(String failure) {
		when(prompts.createPrompt(any(), any())).thenReturn(request);
		switch (failure) {
			case "timeout" -> when(llm.generateAnswer(request)).thenThrow(new LlmException(LlmErrorCode.LLM_TIMEOUT));
			case "invalid" -> when(llm.generateAnswer(request)).thenReturn(new LlmResponseDto("정리할 수 없습니다."));
			default -> when(turns.findPreviousTurns(anyLong(), anyInt())).thenThrow(new QueryTimeoutException("db"));
		}

		SubQuestionPlan plan = planner(true).plan(attempt(10L, 7L, "유심 재발급"));

		assertThat(plan.subQuestions()).containsExactly("유심 재발급");
		assertThat(plan.contextUsed()).isFalse();
		assertThat(plan.source()).isEqualTo(SubQuestionPlan.Source.LLM_FAILED);
	}

	@Test
	@DisplayName("판정을 끄면 판정 LLM과 이전 대화를 쓰지 않고 원문 하나로 진행한다")
	void skipsJudgeWhenDisabled() {
		SubQuestionPlan plan = planner(false).plan(attempt(10L, 7L, "유심 재발급"));

		assertThat(plan.subQuestions()).containsExactly("유심 재발급");
		assertThat(plan.source()).isEqualTo(SubQuestionPlan.Source.ORIGINAL);
		verifyNoInteractions(turns, prompts, llm);
	}

	@Test
	@DisplayName("재검색은 판정 LLM 없이 원본 시도의 하위 질문을 순서대로 다시 쓰고, 하위 질문이 없던 행은 원문을 쓴다")
	void reusesSourceSubQuestionsForResearch() {
		when(questionLogs.findAllByAttemptIdOrderBySubQuestionIndexAsc(5L)).thenReturn(List.of(
				QuestionLog.builder().userQuestion("요금이랑 데이터").subQuestion("월 요금").subQuestionIndex(0).build(),
				QuestionLog.builder().userQuestion("요금이랑 데이터").subQuestionIndex(1).build()));

		SubQuestionPlan plan = planner(true).plan(research(5L, "요금이랑 데이터"));

		assertThat(plan.subQuestions()).containsExactly("월 요금", "요금이랑 데이터");
		assertThat(plan.source()).isEqualTo(SubQuestionPlan.Source.REUSED);
		verifyNoInteractions(llm, turns);
	}

	@Test
	@DisplayName("원본 시도와 연결된 질문 로그가 없으면 재검색은 원문 하나로 진행한다")
	void researchFallsBackToOriginalWithoutSourceLogs() {
		when(questionLogs.findAllByAttemptIdOrderBySubQuestionIndexAsc(5L)).thenReturn(List.of());

		SubQuestionPlan plan = planner(true).plan(research(5L, "유심 재발급"));

		assertThat(plan.subQuestions()).containsExactly("유심 재발급");
		verify(questionLogs).findAllByAttemptIdOrderBySubQuestionIndexAsc(5L);
		verifyNoInteractions(llm);
	}

	private AnswerAttemptsHistory attempt(Long id, Long conversationId, String question) {
		return AnswerAttemptsHistory.builder().id(id).userId(1L).conversationId(conversationId).question(question)
				.status("PENDING").build();
	}

	private AnswerAttemptsHistory research(Long sourceAttemptId, String question) {
		return AnswerAttemptsHistory.builder().id(20L).userId(1L).question(question).status("PENDING")
				.sourceAttemptId(sourceAttemptId).intent(Intent.GENERAL).build();
	}
}
