package com.ubot.chat.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.ubot.ai.dto.AiAnswer;
import com.ubot.ai.dto.AnswerMaterials;
import com.ubot.ai.dto.SubQuestionFaqs;
import com.ubot.ai.service.AiService;
import com.ubot.chat.entity.AnswerAttemptsHistory;
import com.ubot.chat.exception.ChatErrorCode;
import com.ubot.faq.dto.response.FaqSearchResponseDto;
import com.ubot.faq.enums.Intent;
import com.ubot.faq.retrieval.FaqRetrievalService;
import com.ubot.faq.service.FaqVectorService;
import com.ubot.unanswered.enums.UnansweredReason;
import com.ubot.unanswered.service.UnansweredQuestionService;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

@DisplayName("하위 질문별 검색·답변 계산 테스트")
class ChatAnswerProcessorSubQuestionTest {
	private static final Runnable NOT_CANCELLED = () -> { };

	private final FaqVectorService vector = mock(FaqVectorService.class);
	private final AiService ai = mock(AiService.class);
	private final UnansweredQuestionService unanswered = mock(UnansweredQuestionService.class);
	private final SubQuestionPlanner planner = mock(SubQuestionPlanner.class);
	private final AnswerAttemptsHistory attempt = AnswerAttemptsHistory.builder()
			.id(100L).userId(1L).question("유심 재발급이랑 해외 로밍 요금 알려줘").status("PENDING").build();
	private ChatAnswerProcessor processor;

	@BeforeEach
	void setUp() {
		processor = new ChatAnswerProcessor(FaqRetrievalService.denseOnly(vector), ai, unanswered,
				ChatTestFixtures.collector(), planner);
		ReflectionTestUtils.setField(processor, "topK", 3);
		ReflectionTestUtils.setField(processor, "confidenceThreshold", 0.75);
		when(ai.generateAnswer(any())).thenReturn(new AiAnswer("답변", null, false));
	}

	@Test
	@DisplayName("하위 질문마다 따로 검색해 질문별로 묶고, 같은 FAQ가 여러 질문에서 걸려도 질문마다 남긴다")
	void searchesEachSubQuestion() {
		plan("유심 재발급 방법", "유심 재발급 비용");
		var howTo = faq(1L, 0.9);
		var cost = faq(2L, 0.8);
		when(vector.getSimilarList("유심 재발급 방법", 3)).thenReturn(List.of(howTo, cost));
		when(vector.getSimilarList("유심 재발급 비용", 3)).thenReturn(List.of(cost));

		ChatAnswerResult result = processor.generateAnswer(attempt, null, NOT_CANCELLED);

		assertThat(result.isFailed()).isFalse();
		assertThat(result.subQuestions()).containsExactly(
				new SubQuestionFaqs("유심 재발급 방법", List.of(howTo, cost)),
				new SubQuestionFaqs("유심 재발급 비용", List.of(cost)));
		AnswerMaterials materials = captureMaterials();
		// 프롬프트의 질문은 원문이고, FAQ는 핸들러에 한 번만 넘어가며 하위 질문별 묶음은 그대로 남습니다.
		assertThat(materials.question()).isEqualTo("유심 재발급이랑 해외 로밍 요금 알려줘");
		assertThat(materials.faqs()).containsExactly(howTo, cost);
		assertThat(materials.subQuestions()).isEqualTo(result.subQuestions());
		verifyNoInteractions(unanswered);
	}

	@Test
	@DisplayName("일부 하위 질문만 근거를 못 찾으면 답변은 만들고 그 하위 질문만 순번과 함께 미답변으로 기록한다")
	void recordsOnlyFailedSubQuestion() {
		plan("유심 재발급", "해외 로밍 요금");
		var reissue = faq(1L, 0.9);
		var roaming = faq(9L, 0.5);
		when(vector.getSimilarList("유심 재발급", 3)).thenReturn(List.of(reissue));
		when(vector.getSimilarList("해외 로밍 요금", 3)).thenReturn(List.of(roaming));

		ChatAnswerResult result = processor.generateAnswer(attempt, null, NOT_CANCELLED);

		assertThat(result.isFailed()).isFalse();
		assertThat(result.subQuestions()).extracting(SubQuestionFaqs::isAnswered).containsExactly(true, false);
		assertThat(captureMaterials().subQuestions().get(1).faqs()).isEmpty();
		verify(unanswered).createUnansweredQuestion(
				100L, 1, "해외 로밍 요금", UnansweredReason.INSUFFICIENT_FAQ, 9L, 0.5);
		verify(unanswered, never()).createUnansweredQuestion(any(), eq(0), anyString(), any(), any(), any());
	}

	@Test
	@DisplayName("모든 하위 질문이 근거를 못 찾으면 각각 기록하고, 후보가 하나라도 있었으면 기준 미달로 실패한다")
	void failsWhenEverySubQuestionFails() {
		plan("유심 재발급", "해외 로밍 요금");
		when(vector.getSimilarList("유심 재발급", 3)).thenReturn(List.of());
		when(vector.getSimilarList("해외 로밍 요금", 3)).thenReturn(List.of(faq(9L, 0.5)));

		ChatAnswerResult result = processor.generateAnswer(attempt, null, NOT_CANCELLED);

		assertThat(result.errorCode()).isEqualTo(ChatErrorCode.INSUFFICIENT_FAQ);
		verify(unanswered).createUnansweredQuestion(100L, 0, "유심 재발급", UnansweredReason.NO_FAQ, null, null);
		verify(unanswered).createUnansweredQuestion(
				100L, 1, "해외 로밍 요금", UnansweredReason.INSUFFICIENT_FAQ, 9L, 0.5);
		verifyNoInteractions(ai);
	}

	@Test
	@DisplayName("모든 하위 질문의 검색 결과가 없으면 검색 결과 없음으로 실패한다")
	void failsWithNoFaqWhenNothingFound() {
		plan("유심 재발급", "해외 로밍 요금");
		when(vector.getSimilarList(anyString(), anyInt())).thenReturn(List.of());

		ChatAnswerResult result = processor.generateAnswer(attempt, null, NOT_CANCELLED);

		assertThat(result.errorCode()).isEqualTo(ChatErrorCode.NO_FAQ);
	}

	@Test
	@DisplayName("하위 질문이 3개를 넘으면 검색·미답변 기록·답변 생성 없이 거절한다")
	void rejectsTooManySubQuestions() {
		plan("a", "b", "c", "d");

		ChatAnswerResult result = processor.generateAnswer(attempt, null, NOT_CANCELLED);

		assertThat(result.errorCode()).isEqualTo(ChatErrorCode.TOO_MANY_SUB_QUESTIONS);
		verifyNoInteractions(vector, unanswered, ai);
	}

	@Test
	@DisplayName("하위 질문이 정확히 3개면 모두 검색한다")
	void acceptsThreeSubQuestions() {
		plan("a", "b", "c");
		when(vector.getSimilarList(anyString(), anyInt())).thenReturn(List.of(faq(1L, 0.9)));

		ChatAnswerResult result = processor.generateAnswer(attempt, null, NOT_CANCELLED);

		assertThat(result.subQuestions()).hasSize(3);
	}

	@Test
	@DisplayName("재검색은 하위 질문마다 고른 의도로 검색하고 미답변으로 기록하지 않는다")
	void researchSearchesEachSubQuestionWithIntent() {
		var research = AnswerAttemptsHistory.builder().id(200L).userId(1L).question("원문").status("PENDING")
				.sourceAttemptId(100L).intent(Intent.STORE_DATA).build();
		when(planner.plan(research)).thenReturn(new SubQuestionPlan(
				List.of("강남역 매장", "해외 로밍 요금"), SubQuestionPlan.Source.REUSED));
		when(vector.getSimilarListByIntent("강남역 매장", Intent.STORE_DATA, 3)).thenReturn(List.of(faq(1L, 0.9)));
		when(vector.getSimilarListByIntent("해외 로밍 요금", Intent.STORE_DATA, 3)).thenReturn(List.of());

		ChatAnswerResult result = processor.generateAnswer(research, null, NOT_CANCELLED);

		assertThat(result.isFailed()).isFalse();
		verify(vector, never()).getSimilarList(anyString(), anyInt());
		verifyNoInteractions(unanswered);
	}

	private void plan(String... subQuestions) {
		when(planner.plan(attempt)).thenReturn(
				new SubQuestionPlan(List.of(subQuestions), SubQuestionPlan.Source.LLM));
	}

	private AnswerMaterials captureMaterials() {
		ArgumentCaptor<AnswerMaterials> captor = ArgumentCaptor.forClass(AnswerMaterials.class);
		verify(ai).generateAnswer(captor.capture());
		return captor.getValue();
	}

	private FaqSearchResponseDto faq(Long id, double similarity) {
		return new FaqSearchResponseDto(id, "질문 " + id, "답변 " + id, similarity, Intent.GENERAL);
	}
}
