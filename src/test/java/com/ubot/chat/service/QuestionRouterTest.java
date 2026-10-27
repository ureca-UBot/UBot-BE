package com.ubot.chat.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.ubot.ai.config.AiInputGuardProperties;
import com.ubot.forbiddenword.service.ForbiddenWordFilterService;
import kr.pe.bab2min.Kiwi;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

/** 실제 Kiwi 모델로 확인합니다. 사례는 평가 데이터(평가 200건, 질문 분리 100건)의 질문입니다. */
@DisplayName("질문 경로 분류 테스트")
class QuestionRouterTest {
	private static Kiwi kiwi;
	private static QuestionRouter router;

	@BeforeAll
	static void loadKiwi() throws Exception {
		kiwi = Kiwi.init(System.getenv().getOrDefault("KIWI_MODEL_PATH", ".kiwi/model"));
		router = new QuestionRouter(new QuestionMorphology(kiwi));
	}

	@AfterAll
	static void closeKiwi() throws Exception {
		kiwi.close();
	}

	@Test
	@DisplayName("형태소 분석이 실패하면 답변을 실패시키지 않고 표지가 없는 것으로 본다")
	void treatsAnalysisFailureAsNoMarks() {
		QuestionMorphology failing = mock(QuestionMorphology.class);
		when(failing.words(any())).thenThrow(new MorphologyException(new Exception("Kiwi 분석 실패")));

		assertThat(new QuestionRouter(failing).route("유심 재발급이랑 비용 알려줘", true)).isEqualTo(QuestionRouter.Route.none());
	}

	@ParameterizedTest
	@CsvSource(delimiter = '|', value = {
			"유심 재발급 방법이랑 해외 로밍 신청 방법 알려줘|접속조사",
			"자동이체 계좌 바꾸는 법하고 카드 바꾸는 법 알려줘|접속조사",
			"로밍 신청은 어떻게 하고 문자 수신은 무료인가요?|연결어미",
			"데이터 이월돼요? 가족 공유도 돼요?|문장 여럿",
			"유심 재발급 방법, 로밍 신청, 소액결제 차단 알려줘|나열어"})
	@DisplayName("질문을 나눌 표지가 있으면 판정 LLM을 부른다")
	void detectsSplitMarks(String question, String mark) {
		QuestionRouter.Route route = router.route(question, false);

		assertThat(route.splitMarks()).contains(mark);
		assertThat(route.needsJudge()).isTrue();
	}

	@ParameterizedTest
	@ValueSource(strings = {
			"요금제 알려줘 그리고 유심 재발급 알려줘",
			"요금제 알려줘 그리고 안녕😊 유심 재발급 알려줘",
			"로밍 신청 방법, 유심 재발급 알려줘"})
	void keepsSplitMarkersAfterInputGuard(String question) {
		var guard = new ChatInputGuardService(mock(ForbiddenWordFilterService.class), new ChatPromptInjectionService(),
				new AiInputGuardProperties(), org.mockito.Mockito.mock(com.ubot.guardreview.service.ReviewCheckService.class));
		String prepared = guard.prepareQuestion(question);

		assertThat(router.route(prepared, false).splitMarks()).contains("나열어");
	}

	@ParameterizedTest
	@ValueSource(strings = {"그럼", "그러면", "그래서", "그리고", "또", "아니", "그건", "그거"})
	void keepsMultiTurnLeadsAfterInputGuard(String lead) {
		var guard = new ChatInputGuardService(mock(ForbiddenWordFilterService.class), new ChatPromptInjectionService(),
				new AiInputGuardProperties(), org.mockito.Mockito.mock(com.ubot.guardreview.service.ReviewCheckService.class));
		String prepared = guard.prepareQuestion(lead + " 유심 재발급 비용은 얼마인가요?");

		assertThat(prepared).startsWith(lead + " ");
		assertThat(router.route(prepared, true).multiTurnMarks()).contains("이어 묻기");
	}

	@ParameterizedTest
	@ValueSource(strings = {
			"휴대폰 신규 가입은 어떻게 하나요?",
			"해외에서 데이터 사용을 차단하고 싶어요.",
			"데이터 1.5GB 요금제 있나요?",
			"요금 빠져나가는 은행 계좌 바꾸고 싶어"})
	@DisplayName("보조용언 앞의 '-고'나 숫자 안의 점은 나눌 표지로 보지 않는다")
	void ignoresNonSplitMarks(String question) {
		assertThat(router.route(question, false).needsJudge()).isFalse();
	}

	@ParameterizedTest
	@CsvSource(delimiter = '|', value = {
			"그거 비용은 얼마예요?|지시어",
			"준비물은요?|서술어 없음",
			"한국으로 전화하면요?|-면요",
			"요금도 확인돼요?|도",
			"아니 예산은 3만원으로 바꿀게|이어 묻기",
			"두 번째 거 비용은요?|순서",
			"취소는 어떻게 해요?|짧음"})
	@DisplayName("이전 대화가 있을 때 멀티턴 표지를 찾는다")
	void detectsMultiTurnMarks(String question, String mark) {
		QuestionRouter.Route route = router.route(question, true);

		assertThat(route.multiTurnMarks()).contains(mark);
		assertThat(route.needsJudge()).isTrue();
	}

	@ParameterizedTest
	@ValueSource(strings = {"해외 로밍은 어떻게 신청하죠?", "분실폰 위치 찾기 서비스 있어요?"})
	@DisplayName("주제를 바꾼 독립 질문은 이전 대화가 있어도 표지가 없다")
	void leavesIndependentQuestion(String question) {
		assertThat(router.route(question, true).needsJudge()).isFalse();
	}

	@ParameterizedTest
	@ValueSource(strings = {"내 정보 알려줘", "내 회선 정보 알려줘", "제 정보 알려 주세요"})
	void completeSelfQuestionsDoNotNeedHistoryOnlyBecauseTheyAreShort(String question) {
		assertThat(router.route(question, true).multiTurnMarks()).doesNotContain("짧음");
		assertThat(router.route(question, true).needsJudge()).isFalse();
	}

	@ParameterizedTest
	@ValueSource(strings = {"요금제 뭐 있어?", "요금제 뭐있냐?", "요금제 어떤 게 있어?", "무슨 요금제 있어요?", "요금제는 어떤게 있어"})
	void existenceQuestionsDoNotNeedHistoryOnlyBecauseTheyAreShort(String question) {
		assertThat(router.route(question, true).multiTurnMarks()).doesNotContain("짧음");
	}

	@ParameterizedTest
	@CsvSource(delimiter = '|', value = {"내 정보도 알려줘|도", "그럼 내 정보 알려줘|이어 묻기", "내 그거 알려줘|지시어"})
	void explicitSelfOwnerDoesNotRemoveOtherContextMarkers(String question, String mark) {
		assertThat(router.route(question, true).multiTurnMarks()).contains(mark);
		assertThat(router.route(question, true).needsJudge()).isTrue();
	}

	@ParameterizedTest
	@ValueSource(strings = {"그거 얼마예요?", "준비물은요?"})
	@DisplayName("이전 대화가 없으면 멀티턴 표지를 보지 않는다")
	void ignoresMultiTurnMarksWithoutHistory(String question) {
		assertThat(router.route(question, false).multiTurnMarks()).isEmpty();
	}
}
