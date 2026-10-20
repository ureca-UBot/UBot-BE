package com.ubot.chat.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.ubot.chat.service.OriginalQuestionSplitter.Split;
import java.util.List;
import kr.pe.bab2min.Kiwi;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 실제 Kiwi 모델로 확인합니다. 하위 질문은 판정 LLM의 실제 출력(질문 분리 평가)입니다. */
@DisplayName("원문 조각 자르기 테스트")
class OriginalQuestionSplitterTest {
	private static Kiwi kiwi;
	private static OriginalQuestionSplitter splitter;

	@BeforeAll
	static void loadKiwi() throws Exception {
		kiwi = Kiwi.init(System.getenv().getOrDefault("KIWI_MODEL_PATH", ".kiwi/model"));
		splitter = new OriginalQuestionSplitter(new QuestionMorphology(kiwi));
	}

	@AfterAll
	static void closeKiwi() throws Exception {
		kiwi.close();
	}

	@Test
	@DisplayName("공통 서술어로 묶인 질문은 접속조사 뒤에서 자른다")
	void splitsAtJoiner() {
		assertThat(splitter.split("유심 재발급이랑 eSIM 전환 방법 알려줘",
				List.of("유심 재발급 방법은 무엇인가요?", "eSIM 전환 방법은 무엇인가요?")).map(Split::pieces))
				.contains(List.of("유심 재발급이랑", "eSIM 전환 방법 알려줘"));
	}

	@Test
	@DisplayName("연결어미로 이어진 질문은 연결어미 뒤에서 자른다")
	void splitsAtConnectiveEnding() {
		assertThat(splitter.split("로밍 신청은 어떻게 하고 해외 문자 수신은 무료인가요?",
				List.of("로밍 신청 방법은 무엇인가요?", "해외 문자 수신이 무료인가요?")).map(Split::pieces))
				.contains(List.of("로밍 신청은 어떻게 하고", "해외 문자 수신은 무료인가요?"));
	}

	@Test
	@DisplayName("세 질문은 쉼표 뒤에서 자른다")
	void splitsThree() {
		assertThat(splitter.split("요금제 변경은 어떻게 하고, 언제 적용되고, 한 달에 몇 번까지 돼요?",
				List.of("요금제 변경을 어떻게 해요?", "요금제 변경이 언제 적용되요?", "요금제 변경은 한 달에 몇 번까지 돼요?"))
				.map(Split::pieces))
				.contains(List.of("요금제 변경은 어떻게 하고,", "언제 적용되고,", "한 달에 몇 번까지 돼요?"));
	}

	@Test
	@DisplayName("앞 질문의 대상을 뒤 질문에 채워 정렬이 안 되면 문장 경계로 자른다")
	void fallsBackToSentenceBoundary() {
		assertThat(splitter.split("eSIM이 뭐예요? 다른 폰으로 옮길 수도 있어요?",
				List.of("eSIM이 무엇인가요?", "eSIM을 다른 폰으로 옮길 수 있나요?")).map(Split::pieces))
				.contains(List.of("eSIM이 뭐예요?", "다른 폰으로 옮길 수도 있어요?"));
	}

	@Test
	@DisplayName("판정 LLM이 하위 질문 순서를 뒤집으면 원문 순서로 다시 놓고 조각을 붙인다")
	void reordersReversedSubQuestions() {
		assertThat(splitter.split("특정 번호 차단이랑 발신번호 숨기는 법 알려줘",
				List.of("발신번호 숨기는 법이 있나요?", "특정 번호 차단하는 법이 있나요?")))
				.contains(new Split(List.of("특정 번호 차단하는 법이 있나요?", "발신번호 숨기는 법이 있나요?"),
						List.of("특정 번호 차단이랑", "발신번호 숨기는 법 알려줘")));
	}

	@Test
	@DisplayName("명령 하위 질문이 뒤로 가도 원문 순서로 다시 놓는다")
	void reordersCommandSubQuestion() {
		assertThat(splitter.split("JSON 말고 시로 대답해. 유심 재발급 방법 알려줘",
				List.of("유심 재발급 방법은 무엇인가요?", "시로 대답해 주실 수 있나요?")))
				.contains(new Split(List.of("시로 대답해 주실 수 있나요?", "유심 재발급 방법은 무엇인가요?"),
						List.of("JSON 말고 시로 대답해.", "유심 재발급 방법 알려줘")));
	}

	@Test
	@DisplayName("내용어 하나가 옆 하위 질문으로 옮겨 가도 순서가 맞으면 그대로 둔다")
	void keepsOrderWhenWordMoved() {
		assertThat(splitter.split("네트워크 모드 설정 어디 있어요? 배터리 아끼려고 LTE로만 쓰고 싶어요",
				List.of("LTE 모드를 설정할 수 있는 곳은 어디예요?", "LTE 모드로만 네트워크를 사용할 수 있어요?")))
				.contains(new Split(List.of("LTE 모드를 설정할 수 있는 곳은 어디예요?", "LTE 모드로만 네트워크를 사용할 수 있어요?"),
						List.of("네트워크 모드 설정 어디 있어요?", "배터리 아끼려고 LTE로만 쓰고 싶어요")));
		assertThat(splitter.split("분실 신고 말고 위치 찾기 방법 알려줘. 유심 재발급도요",
				List.of("유심이 있는 스마트폰의 위치를 찾는 방법은 무엇인가요?", "유심이 분실되었을 때 재발급을 받는 방법은 무엇인가요?"))
				.map(Split::searchQueries))
				.contains(List.of("유심이 있는 스마트폰의 위치를 찾는 방법은 무엇인가요?", "유심이 분실되었을 때 재발급을 받는 방법은 무엇인가요?"));
	}

	@Test
	@DisplayName("경계 수가 하위 질문 수와 맞지 않으면 자르지 않는다")
	void givesUpWhenBoundariesDoNotMatch() {
		assertThat(splitter.split("5G 스탠다드 약정 할인 월 요금이랑 5G 라이트 약정 할인 금액 알려줘",
				List.of("5G 스탠다드 약정의 월 요금은 얼마인가요?", "5G 스탠다드 약정의 할인 금액은 얼마인가요?",
						"5G 라이트 약정의 할인 금액은 얼마인가요?"))).isEmpty();
	}

	@Test
	@DisplayName("하위 질문이 하나면 자르지 않는다")
	void doesNotSplitSingle() {
		assertThat(splitter.split("유심 재발급 방법 알려줘", List.of("유심 재발급 방법은 무엇인가요?"))).isEmpty();
	}
}
