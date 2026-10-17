package com.ubot.chat.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.ubot.ai.dto.AiAnswer;
import com.ubot.ai.dto.AnswerMaterials;
import com.ubot.ai.dto.ConversationTurn;
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
		// 기본값은 유사도 기준과 같은 0.75입니다. 이전 대화 기준을 튜닝해 올렸을 때의 동작을 보려고 0.80으로 둡니다.
		ReflectionTestUtils.setField(processor, "searchFirstHistoryThreshold", 0.80);
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
				new SubQuestionFaqs("유심 재발급 방법", List.of(howTo, cost), "유심 재발급 방법"),
				new SubQuestionFaqs("유심 재발급 비용", List.of(cost), "유심 재발급 비용"));
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
				100L, 1, "해외 로밍 요금", "해외 로밍 요금", UnansweredReason.INSUFFICIENT_FAQ, 9L, 0.5);
		verify(unanswered, never()).createUnansweredQuestion(any(), eq(0), anyString(), anyString(), any(), any(), any());
	}

	@Test
	@DisplayName("모든 하위 질문이 근거를 못 찾으면 각각 기록하고, 후보가 하나라도 있었으면 기준 미달로 실패한다")
	void failsWhenEverySubQuestionFails() {
		plan("유심 재발급", "해외 로밍 요금");
		when(vector.getSimilarList("유심 재발급", 3)).thenReturn(List.of());
		when(vector.getSimilarList("해외 로밍 요금", 3)).thenReturn(List.of(faq(9L, 0.5)));

		ChatAnswerResult result = processor.generateAnswer(attempt, null, NOT_CANCELLED);

		assertThat(result.errorCode()).isEqualTo(ChatErrorCode.INSUFFICIENT_FAQ);
		verify(unanswered).createUnansweredQuestion(100L, 0, "유심 재발급", "유심 재발급", UnansweredReason.NO_FAQ, null, null);
		verify(unanswered).createUnansweredQuestion(
				100L, 1, "해외 로밍 요금", "해외 로밍 요금", UnansweredReason.INSUFFICIENT_FAQ, 9L, 0.5);
		verifyNoInteractions(ai);
	}

	@Test
	@DisplayName("원문을 자를 근거가 없어 원문 조각이 비어 있으면 미답변 질문에는 원문 전체를 남긴다")
	void recordsOriginalQuestionWhenPieceIsMissing() {
		when(planner.plan(attempt)).thenReturn(new SubQuestionPlan(List.of(
				new SubQuestionPlan.SubQuestion("유심 재발급", null),
				new SubQuestionPlan.SubQuestion("해외 로밍 요금", null)),
				SubQuestionPlan.Source.LLM, List.of(), false, null, false));
		when(vector.getSimilarList("유심 재발급", 3)).thenReturn(List.of(faq(1L, 0.9)));
		when(vector.getSimilarList("해외 로밍 요금", 3)).thenReturn(List.of());

		ChatAnswerResult result = processor.generateAnswer(attempt, null, NOT_CANCELLED);

		assertThat(result.isFailed()).isFalse();
		assertThat(result.subQuestions()).extracting(SubQuestionFaqs::userText).containsOnlyNulls();
		verify(unanswered).createUnansweredQuestion(
				100L, 1, "해외 로밍 요금", "유심 재발급이랑 해외 로밍 요금 알려줘", UnansweredReason.NO_FAQ, null, null);
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
				List.of("강남역 매장", "해외 로밍 요금"), SubQuestionPlan.Source.LLM));
		when(vector.getSimilarListByIntent("강남역 매장", Intent.STORE_DATA, 3)).thenReturn(List.of(faq(1L, 0.9)));
		when(vector.getSimilarListByIntent("해외 로밍 요금", Intent.STORE_DATA, 3)).thenReturn(List.of());

		ChatAnswerResult result = processor.generateAnswer(research, null, NOT_CANCELLED);

		assertThat(result.isFailed()).isFalse();
		verify(vector, never()).getSimilarList(anyString(), anyInt());
		verifyNoInteractions(unanswered);
	}

	@Test
	@DisplayName("원문 먼저 검색이 기준을 넘으면 판정 LLM 없이 그 결과로 답한다")
	void answersWithOriginalSearchWhenItPasses() {
		var plain = AnswerAttemptsHistory.builder().id(300L).userId(1L).question("해외 로밍은 어떻게 신청하나요?")
				.status("PENDING").build();
		when(planner.plan(plain)).thenReturn(
				SubQuestionPlan.searchFirst(plain.getQuestion(), List.of(), QuestionRouter.Route.none()));
		var roaming = faq(29L, 0.99);
		when(vector.getSimilarList("해외 로밍은 어떻게 신청하나요?", 3)).thenReturn(List.of(roaming));

		ChatAnswerResult result = processor.generateAnswer(plain, null, NOT_CANCELLED);

		assertThat(result.subQuestions()).containsExactly(
				new SubQuestionFaqs("해외 로밍은 어떻게 신청하나요?", List.of(roaming), "해외 로밍은 어떻게 신청하나요?"));
		verify(planner, never()).judge(any(), any());
		verify(vector).getSimilarList("해외 로밍은 어떻게 신청하나요?", 3);
	}

	@Test
	@DisplayName("원문 먼저 검색이 기준에 못 미치면 판정한 하위 질문으로 다시 검색하고, 원문 조각을 답변 자료에 넘긴다")
	void judgesWhenOriginalSearchFails() {
		var question = "로밍 신청은 어떻게 하고 문자 수신은 무료인가요?";
		var plain = AnswerAttemptsHistory.builder().id(301L).userId(1L).question(question).status("PENDING").build();
		SubQuestionPlan first = SubQuestionPlan.searchFirst(question, List.of(), QuestionRouter.Route.none());
		when(planner.plan(plain)).thenReturn(first);
		when(planner.judge(plain, first)).thenReturn(new SubQuestionPlan(List.of(
				new SubQuestionPlan.SubQuestion("로밍 신청 방법은 무엇인가요?", "로밍 신청은 어떻게 하고"),
				new SubQuestionPlan.SubQuestion("해외 문자 수신은 무료인가요?", "문자 수신은 무료인가요?")),
				SubQuestionPlan.Source.LLM, List.of(), false, null, false));
		when(vector.getSimilarList(question, 3)).thenReturn(List.of(faq(190L, 0.74)));
		var apply = faq(29L, 0.9);
		var sms = faq(35L, 0.85);
		when(vector.getSimilarList("로밍 신청 방법은 무엇인가요?", 3)).thenReturn(List.of(apply));
		when(vector.getSimilarList("해외 문자 수신은 무료인가요?", 3)).thenReturn(List.of(sms));

		ChatAnswerResult result = processor.generateAnswer(plain, null, NOT_CANCELLED);

		assertThat(result.subQuestions()).extracting(SubQuestionFaqs::searchQuery, SubQuestionFaqs::userText)
				.containsExactly(
						org.assertj.core.groups.Tuple.tuple("로밍 신청 방법은 무엇인가요?", "로밍 신청은 어떻게 하고"),
						org.assertj.core.groups.Tuple.tuple("해외 문자 수신은 무료인가요?", "문자 수신은 무료인가요?"));
		assertThat(captureMaterials().subQuestions()).extracting(SubQuestionFaqs::userText)
				.containsExactly("로밍 신청은 어떻게 하고", "문자 수신은 무료인가요?");
	}

	@Test
	@DisplayName("원문 검색이 기준에 못 미친 뒤 판정 결과가 원문 그대로면 다시 검색하지 않고 그 기준으로 답할 수 없음 처리한다")
	void reusesFailedOriginalSearch() {
		var history = List.of(new ConversationTurn("일본에서 3일 쓰면 얼마예요?", List.of("일본 로밍 3일 요금"), "하루 1만 원입니다."));
		var question = "신청 취소는 어떻게 해요?";
		var plain = AnswerAttemptsHistory.builder().id(303L).userId(1L).conversationId(7L).question(question)
				.status("PENDING").build();
		SubQuestionPlan first = SubQuestionPlan.searchFirst(question, history, QuestionRouter.Route.none());
		when(planner.plan(plain)).thenReturn(first);
		// 가드가 검색어를 막아 원문 전체로 되돌린 경우입니다.
		when(planner.judge(plain, first)).thenReturn(new SubQuestionPlan(List.of(
				new SubQuestionPlan.SubQuestion(question, question)),
				SubQuestionPlan.Source.LLM, history, true, null, false));
		// 0.77은 유사도 기준(0.75)은 넘지만, 이 검색이 넘지 못한 이전 대화 기준(0.80)에는 못 미칩니다.
		when(vector.getSimilarList(question, 3)).thenReturn(List.of(faq(147L, 0.77)));

		ChatAnswerResult result = processor.generateAnswer(plain, null, NOT_CANCELLED);

		assertThat(result.errorCode()).isEqualTo(ChatErrorCode.INSUFFICIENT_FAQ);
		verify(vector, times(1)).getSimilarList(question, 3);
		verify(unanswered).createUnansweredQuestion(
				303L, 0, question, question, UnansweredReason.INSUFFICIENT_FAQ, 147L, 0.77);
		verifyNoInteractions(ai);
	}

	@Test
	@DisplayName("원문 검색이 기준에 못 미친 뒤 막힌 검색어를 원문 조각으로 돌렸으면 조각은 새로 검색한다")
	void searchesPieceAfterFailedOriginalSearch() {
		var question = "해지 안 되나요? 그리고 위약금은요?";
		var plain = AnswerAttemptsHistory.builder().id(304L).userId(1L).question(question).status("PENDING").build();
		SubQuestionPlan first = SubQuestionPlan.searchFirst(question, List.of(), QuestionRouter.Route.none());
		when(planner.plan(plain)).thenReturn(first);
		when(planner.judge(plain, first)).thenReturn(new SubQuestionPlan(List.of(
				new SubQuestionPlan.SubQuestion("해지 안 되나요?", "해지 안 되나요?"),
				new SubQuestionPlan.SubQuestion("해지 위약금은 얼마인가요?", "그리고 위약금은요?")),
				SubQuestionPlan.Source.LLM, List.of(), false, null, false));
		when(vector.getSimilarList(question, 3)).thenReturn(List.of(faq(1L, 0.6)));
		var cancel = faq(2L, 0.9);
		var penalty = faq(3L, 0.9);
		when(vector.getSimilarList("해지 안 되나요?", 3)).thenReturn(List.of(cancel));
		when(vector.getSimilarList("해지 위약금은 얼마인가요?", 3)).thenReturn(List.of(penalty));

		ChatAnswerResult result = processor.generateAnswer(plain, null, NOT_CANCELLED);

		assertThat(result.subQuestions()).extracting(SubQuestionFaqs::isAnswered).containsExactly(true, true);
		verify(vector).getSimilarList("해지 안 되나요?", 3);
	}

	@Test
	@DisplayName("이전 대화 기준이 기본값(유사도 기준과 같음)이면 이전 대화가 있어도 원문 검색이 유사도 기준만 넘으면 판정 없이 끝난다")
	void usesConfidenceThresholdWithHistoryByDefault() {
		ReflectionTestUtils.setField(processor, "searchFirstHistoryThreshold", 0.75);
		var history = List.of(new ConversationTurn("일본에서 3일 쓰면 얼마예요?", List.of("일본 로밍 3일 요금"), "하루 1만 원입니다."));
		var question = "신청 취소는 어떻게 해요?";
		var plain = AnswerAttemptsHistory.builder().id(305L).userId(1L).conversationId(7L).question(question)
				.status("PENDING").build();
		when(planner.plan(plain)).thenReturn(SubQuestionPlan.searchFirst(question, history, QuestionRouter.Route.none()));
		var cancel = faq(147L, 0.77);
		when(vector.getSimilarList(question, 3)).thenReturn(List.of(cancel));

		ChatAnswerResult result = processor.generateAnswer(plain, null, NOT_CANCELLED);

		assertThat(result.subQuestions()).containsExactly(new SubQuestionFaqs(question, List.of(cancel), question));
		verify(planner, never()).judge(any(), any());
	}

	@Test
	@DisplayName("이전 대화 기준을 유사도 기준보다 높이면 이전 대화가 있을 때 원문 검색은 그 기준을 넘어야 판정 없이 끝난다")
	void usesHigherThresholdWithHistory() {
		var history = List.of(new ConversationTurn("일본에서 3일 쓰면 얼마예요?", List.of("일본 로밍 3일 요금"), "하루 1만 원입니다."));
		var question = "신청 취소는 어떻게 해요?";
		var plain = AnswerAttemptsHistory.builder().id(302L).userId(1L).conversationId(7L).question(question)
				.status("PENDING").build();
		SubQuestionPlan first = SubQuestionPlan.searchFirst(question, history, QuestionRouter.Route.none());
		when(planner.plan(plain)).thenReturn(first);
		when(planner.judge(plain, first)).thenReturn(new SubQuestionPlan(List.of(
				new SubQuestionPlan.SubQuestion("로밍 신청 취소는 어떻게 하나요?", question)),
				SubQuestionPlan.Source.LLM, history, true, null, false));
		// 0.77은 유사도 기준(0.75)은 넘지만 이전 대화가 있을 때의 기준(0.80)에는 못 미칩니다.
		when(vector.getSimilarList(question, 3)).thenReturn(List.of(faq(147L, 0.77)));
		when(vector.getSimilarList("로밍 신청 취소는 어떻게 하나요?", 3)).thenReturn(List.of(faq(199L, 0.88)));

		processor.generateAnswer(plain, null, NOT_CANCELLED);

		verify(planner).judge(plain, first);
		// 멀티턴이라 답변 자료에 이전 대화가 들어갑니다.
		assertThat(captureMaterials().history()).isEqualTo(history);
	}

	@Test
	@DisplayName("멀티턴이 아니면 이전 대화가 있어도 답변 자료에 넣지 않는다")
	void leavesHistoryOutWhenNotMultiTurn() {
		var history = List.of(new ConversationTurn("유심 재발급 방법", List.of("유심 재발급 방법"), "매장에서 가능합니다."));
		when(planner.plan(attempt)).thenReturn(new SubQuestionPlan(List.of(
				new SubQuestionPlan.SubQuestion("해외 로밍 신청 방법", "해외 로밍 신청")),
				SubQuestionPlan.Source.LLM, history, false, null, false));
		when(vector.getSimilarList("해외 로밍 신청 방법", 3)).thenReturn(List.of(faq(29L, 0.9)));

		processor.generateAnswer(attempt, null, NOT_CANCELLED);

		assertThat(captureMaterials().history()).isEmpty();
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
