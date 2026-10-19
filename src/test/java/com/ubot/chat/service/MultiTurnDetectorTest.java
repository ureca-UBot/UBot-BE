package com.ubot.chat.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.ubot.ai.dto.ConversationTurn;
import java.util.List;
import kr.pe.bab2min.Kiwi;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 실제 Kiwi 모델로 확인합니다. 사례는 평가 데이터의 멀티턴 질문과 판정 LLM의 실제 출력입니다. */
@DisplayName("멀티턴 판정 테스트")
class MultiTurnDetectorTest {
	private static final QuestionRouter.Route NO_MARK = QuestionRouter.Route.none();
	private static Kiwi kiwi;
	private static MultiTurnDetector detector;

	@BeforeAll
	static void loadKiwi() throws Exception {
		kiwi = Kiwi.init(System.getenv().getOrDefault("KIWI_MODEL_PATH", ".kiwi/model"));
		detector = new MultiTurnDetector(new QuestionMorphology(kiwi));
	}

	@AfterAll
	static void closeKiwi() throws Exception {
		kiwi.close();
	}

	@Test
	@DisplayName("검색어가 이전 대화에서만 온 말을 빌렸으면 멀티턴이다")
	void detectsBorrowedContext() {
		var history = turns("유심 재발급 방법 알려줘", "신분증을 지참해 매장을 방문하면 재발급할 수 있습니다.");

		assertThat(detector.isMultiTurn("준비물은 뭐예요?", history, List.of("유심 재발급 준비물은 무엇인가요?"), NO_MARK)).isTrue();
	}

	@Test
	@DisplayName("서술어만 빌려 온 경우도 멀티턴이다")
	void detectsBorrowedPredicate() {
		var history = turns("데이터 잔여량은 어디서 확인해요?", "앱에서 확인할 수 있습니다.");

		assertThat(detector.isMultiTurn("요금 보여줘", history, List.of("요금은 어디서 확인해요?"), NO_MARK)).isTrue();
	}

	@Test
	@DisplayName("검색어에 이전 대화를 가리키는 말이 남았으면 멀티턴이다")
	void detectsPointerLeft() {
		var history = turns("가까운 매장 찾는 방법 알려줘", "앱의 매장 찾기에서 확인할 수 있습니다.");

		assertThat(detector.isMultiTurn("거기 영업시간 알려줘", history, List.of("거기 영업시간은 무엇인가요?"), NO_MARK)).isTrue();
	}

	@Test
	@DisplayName("주제를 바꾼 독립 질문은 멀티턴이 아니다")
	void leavesIndependentQuestion() {
		var history = turns("유심 재발급 방법 알려줘", "신분증을 지참해 매장을 방문하면 재발급할 수 있습니다.");

		assertThat(detector.isMultiTurn("해외 로밍은 어떻게 신청하죠?", history,
				List.of("해외 로밍을 어떻게 신청하나요?"), NO_MARK)).isFalse();
	}

	@Test
	@DisplayName("원문에 멀티턴 표지가 있으면 멀티턴이다")
	void followsRouterMark() {
		var history = turns("해지 방법 알려줘", "고객센터에서 해지할 수 있습니다.");
		var marked = new QuestionRouter.Route(List.of(), List.of("짧음"));

		assertThat(detector.isMultiTurn("할부금은 어떻게 돼요?", history, List.of("할부금은 어떻게 돼요?"), marked)).isTrue();
	}

	@Test
	@DisplayName("이전 대화가 없으면 멀티턴이 아니다")
	void needsHistory() {
		var marked = new QuestionRouter.Route(List.of(), List.of("지시어"));

		assertThat(detector.isMultiTurn("그거 얼마예요?", List.of(), List.of("그거 얼마예요?"), marked)).isFalse();
	}

	private static List<ConversationTurn> turns(String question, String answer) {
		return List.of(new ConversationTurn(question, answer));
	}
}
