package com.ubot.chat.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.ubot.ai.dto.ConversationTurn;
import com.ubot.chat.service.SubQuestionGuard.Outcome;
import com.ubot.chat.service.SubQuestionGuard.Reason;
import com.ubot.chat.service.SubQuestionGuard.Result;
import java.util.List;
import kr.pe.bab2min.Kiwi;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 실제 Kiwi 모델로 확인합니다. 모델 경로는 Gradle test 작업이 KIWI_MODEL_PATH로 넘깁니다. 사례는 질문 정리 테스트 보고서에서 가져왔습니다. */
@DisplayName("하위 질문 가드 테스트")
class SubQuestionGuardTest {
	private static Kiwi kiwi;
	private static SubQuestionGuard guard;

	@BeforeAll
	static void loadKiwi() throws Exception {
		kiwi = Kiwi.init(System.getenv().getOrDefault("KIWI_MODEL_PATH", ".kiwi/model"));
		guard = new SubQuestionGuard(kiwi);
	}

	@AfterAll
	static void closeKiwi() throws Exception {
		kiwi.close();
	}

	@Test
	@DisplayName("원문과 이전 대화에 근거가 있는 하위 질문은 그대로 둔다")
	void passesGroundedSubQuestions() {
		Result result = guard.check("유심 재발급 방법이랑 해외 로밍 요금 알려주세요",
				turns("해외 여행 전 준비할 것", "출국 전에 로밍을 신청하고 유심 상태를 확인하세요."),
				List.of("유심 재발급 방법 알려주세요", "해외 로밍 요금 알려주세요"));

		assertThat(result.outcome()).isEqualTo(Outcome.PASSED);
		assertThat(result.subQuestions()).containsExactly("유심 재발급 방법 알려주세요", "해외 로밍 요금 알려주세요");
	}

	@Test
	@DisplayName("원문과 이전 대화에 없는 문자 체계의 글자를 지운다")
	void removesForeignScripts() {
		Result result = guard.check("제주도 연동 매장 예약 가능해요?",
				turns("매장 예약 방법", "앱에서 원하는 매장을 골라 예약할 수 있습니다."),
				List.of("제주도 연동 매장 예약 가능吗?"));

		assertThat(result.subQuestions()).containsExactly("제주도 연동 매장 예약 가능?");
		assertThat(result.reasons()).containsExactly(Reason.FOREIGN_SCRIPT_REMOVED);
	}

	@Test
	@DisplayName("깨진 숫자는 가까운 출처 숫자로 바꾸고 뒤에 붙은 깨진 글자를 지운다")
	void fixesBrokenNumber() {
		Result result = guard.check("거기 주차 되는지 알려줘",
				turns("강남역 근처 매장 위치", "강남역 근처에는 강남직영점(서울 강남구 테헤란로 101)이 있습니다."),
				List.of("강남직영점(서울 강남구 테헤란로 10이나)에 주차 되는지 알려줘"));

		assertThat(result.subQuestions()).containsExactly("강남직영점(서울 강남구 테헤란로 101)에 주차 되는지 알려줘");
		assertThat(result.reasons()).contains(Reason.NUMBER_FIXED);
	}

	@Test
	@DisplayName("가까운 출처 숫자가 없으면 원문으로 돌아간다")
	void fallsBackOnUnrecoverableNumber() {
		Result result = guard.check("유심 재발급 비용 얼마예요?", List.of(), List.of("유심 재발급 비용 999원인가요?"));

		assertThat(result.outcome()).isEqualTo(Outcome.FALLBACK);
		assertThat(result.reasons()).containsExactly(Reason.NUMBER_UNRECOVERABLE);
		assertThat(result.subQuestions()).containsExactly("유심 재발급 비용 얼마예요?");
	}

	@Test
	@DisplayName("원문에 의문사가 없으면 묻는 꼴만 바꾼 것으로 보고 새 의문사를 허용한다")
	void allowsInterrogativeWhenOriginalHasNone() {
		Result result = guard.check("VIP 혜택이랑 VVIP 혜택 각각 알려줘", List.of(),
				List.of("VIP 혜택이 뭐예요?", "VVIP 혜택이 뭐예요?"));

		assertThat(result.outcome()).isEqualTo(Outcome.PASSED);
	}

	@Test
	@DisplayName("원문의 의문사가 다른 의문사로 바뀌면 원문으로 돌아간다")
	void fallsBackOnNewInterrogative() {
		Result result = guard.check("며칠부터 정지돼요?",
				turns("요금 연체 시 불이익", "연체가 계속되면 이용이 정지될 수 있습니다."),
				List.of("요금 연체 시 이용이 정지되는 기간은 몇 개월부터인가요?"));

		assertThat(result.outcome()).isEqualTo(Outcome.FALLBACK);
		assertThat(result.reasons()).containsExactly(Reason.NEW_INTERROGATIVE);
	}

	@Test
	@DisplayName("원문의 키워드가 하위 질문 어디에도 없으면 원문으로 돌아간다")
	void fallsBackOnMissingKeyword() {
		Result result = guard.check("분실폰 위치 찾기 서비스 있어요?",
				turns("휴대폰 분실 정지 신청 방법", "고객센터나 앱에서 분실 정지를 신청할 수 있습니다."),
				List.of("휴대폰 분실 정지 서비스가 있어요?"));

		assertThat(result.outcome()).isEqualTo(Outcome.FALLBACK);
		assertThat(result.reasons()).containsExactly(Reason.KEYWORD_MISSING);
	}

	@Test
	@DisplayName("이전 질문을 옮겨 온 하위 질문은 버린다")
	void dropsSubQuestionCopiedFromHistory() {
		Result result = guard.check("분실폰 위치 찾기 서비스 있어요?",
				turns("휴대폰 분실 정지 신청 방법", "고객센터나 앱에서 분실 정지를 신청할 수 있습니다."),
				List.of("휴대폰 분실 정지 신청 방법은 무엇인가요?", "분실폰 위치 찾기 서비스 있어요?"));

		assertThat(result.subQuestions()).containsExactly("분실폰 위치 찾기 서비스 있어요?");
		assertThat(result.reasons()).containsExactly(Reason.HISTORY_ONLY_DROPPED);
	}

	@Test
	@DisplayName("동사로 대상을 물은 생략형 질문은 이전 대화의 대상을 넣어도 버리지 않는다")
	void keepsRewriteAnchoredByVerb() {
		Result result = guard.check("보내는 건요?",
				turns("해외에서 문자 수신 요금", "해외에서 문자를 받을 때는 요금이 없습니다."),
				List.of("해외에서 문자 보낼 때 요금은요?"));

		assertThat(result.outcome()).isEqualTo(Outcome.PASSED);
		assertThat(result.subQuestions()).containsExactly("해외에서 문자 보낼 때 요금은요?");
	}

	@Test
	@DisplayName("출처에 없는 명사는 뒤에 붙은 조사와 함께 지운다")
	void removesUngroundedNoun() {
		Result result = guard.check("신청은 어떻게 해요?",
				turns("해외 로밍 요금", "로밍 패스를 이용하시면 하루 11,000원에 데이터를 쓸 수 있습니다."),
				List.of("해외 로밍 신청 절차는 어떻게 해요?"));

		assertThat(result.subQuestions()).containsExactly("해외 로밍 신청 어떻게 해요?");
		assertThat(result.reasons()).containsExactly(Reason.NOUN_REMOVED);
	}

	@Test
	@DisplayName("관형형 뒤의 의존명사와 '하다'가 붙은 명사는 지우지 않는다")
	void keepsGrammaticalNouns() {
		Result result = guard.check("카드로도 돼요?",
				turns("자동이체 계좌 변경 방법", "앱에서 자동이체 계좌를 바꿀 수 있습니다."),
				List.of("자동이체를 카드로 설정할 수 있나요?"));

		assertThat(result.outcome()).isEqualTo(Outcome.PASSED);
	}

	@Test
	@DisplayName("원문 표기와 조금 다른 키워드는 원문 표기로 바꾼다")
	void restoresOriginalSpelling() {
		Result result = guard.check("데이타 쿠폰 사용법이랑 데이타 선물하기 방법", List.of(),
				List.of("데이터 쿠폰 사용법", "데이타 선물하기 방법"));

		assertThat(result.subQuestions()).containsExactly("데이타 쿠폰 사용법", "데이타 선물하기 방법");
		assertThat(result.reasons()).containsExactly(Reason.KEYWORD_FIXED);
	}

	@Test
	@DisplayName("내용어가 없는 하위 질문은 버린다")
	void dropsSubQuestionWithoutContent() {
		Result result = guard.check("안녕하세요. 데이터 잔여량 확인은?", List.of(),
				List.of("안녕하세요", "데이터 잔여량 확인은?"));

		assertThat(result.subQuestions()).containsExactly("데이터 잔여량 확인은?");
		assertThat(result.reasons()).containsExactly(Reason.NO_CONTENT_DROPPED);
	}

	@Test
	@DisplayName("정리한 뒤 같아진 하위 질문은 하나로 합친다")
	void dropsDuplicate() {
		Result result = guard.check("유심 재발급 방법 알려줘", List.of(),
				List.of("유심 재발급 방법 알려줘", "유심 재발급 방법 알려줘"));

		assertThat(result.subQuestions()).containsExactly("유심 재발급 방법 알려줘");
		assertThat(result.reasons()).containsExactly(Reason.DUPLICATE_DROPPED);
	}

	private static List<ConversationTurn> turns(String question, String answer) {
		return List.of(new ConversationTurn(List.of(question), answer));
	}
}
