package com.ubot.chat.service;

import static org.assertj.core.api.Assertions.assertThat;

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
				List.of("유심 재발급 방법은 무엇인가요?", "eSIM 전환 방법은 무엇인가요?")))
				.contains(List.of("유심 재발급이랑", "eSIM 전환 방법 알려줘"));
	}

	@Test
	@DisplayName("연결어미로 이어진 질문은 연결어미 뒤에서 자른다")
	void splitsAtConnectiveEnding() {
		assertThat(splitter.split("로밍 신청은 어떻게 하고 해외 문자 수신은 무료인가요?",
				List.of("로밍 신청 방법은 무엇인가요?", "해외 문자 수신이 무료인가요?")))
				.contains(List.of("로밍 신청은 어떻게 하고", "해외 문자 수신은 무료인가요?"));
	}

	@Test
	@DisplayName("세 질문은 쉼표 뒤에서 자른다")
	void splitsThree() {
		assertThat(splitter.split("요금제 변경은 어떻게 하고, 언제 적용되고, 한 달에 몇 번까지 돼요?",
				List.of("요금제 변경을 어떻게 해요?", "요금제 변경이 언제 적용되요?", "요금제 변경은 한 달에 몇 번까지 돼요?")))
				.contains(List.of("요금제 변경은 어떻게 하고,", "언제 적용되고,", "한 달에 몇 번까지 돼요?"));
	}

	@Test
	@DisplayName("앞 질문의 대상을 뒤 질문에 채워 정렬이 안 되면 문장 경계로 자른다")
	void fallsBackToSentenceBoundary() {
		assertThat(splitter.split("eSIM이 뭐예요? 다른 폰으로 옮길 수도 있어요?",
				List.of("eSIM이 무엇인가요?", "eSIM을 다른 폰으로 옮길 수 있나요?")))
				.contains(List.of("eSIM이 뭐예요?", "다른 폰으로 옮길 수도 있어요?"));
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
