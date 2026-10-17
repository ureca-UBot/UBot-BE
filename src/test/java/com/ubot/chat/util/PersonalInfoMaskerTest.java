package com.ubot.chat.util;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

@DisplayName("질문 개인정보 마스킹 테스트")
class PersonalInfoMaskerTest {

	@ParameterizedTest
	@CsvSource(delimiter = '|', value = {
			"제 번호 010-1234-5678로 연락 주세요|제 번호 010-****-5678로 연락 주세요",
			"01012345678 해지돼요?|010-****-5678 해지돼요?",
			"011 123 4567 번호 이동|011-****-4567 번호 이동",
			"주민번호 900101-1234567 입력했어요|주민번호 900101-******* 입력했어요",
			"9001012345678 맞나요|900101-******* 맞나요",
			"카드 1234-5678-9012-3456 등록|카드 ****-****-****-3456 등록",
			"카드 1234567890123456 등록|카드 ****-****-****-3456 등록",
			"메일 minha.yu@naver.com 으로 보내 주세요|메일 m***@naver.com 으로 보내 주세요",
			"5G 요금제 55000원은 얼마예요|5G 요금제 55000원은 얼마예요",
			"고객센터 114 번호가 뭐예요|고객센터 114 번호가 뭐예요",
			"매장 전화 02-1234-5678|매장 전화 02-1234-5678",
			"+82 10-1234-5678 로밍 돼요?|010-****-5678 로밍 돼요?",
			"+821012345678 번호예요|010-****-5678 번호예요",
			"010 - 1234 - 5678 이에요|010-****-5678 이에요",
			"010.1234.5678 이에요|010-****-5678 이에요",
			"900101 - 1234567 이에요|900101-******* 이에요",
			"카드 1234 5678 9012 3456 등록|카드 ****-****-****-3456 등록",
			"카드 1234.5678.9012.3456 등록|카드 ****-****-****-3456 등록"
	})
	@DisplayName("개인정보만 가리고 나머지 문장은 그대로 둔다")
	void masksPersonalInfo(String input, String expected) {
		assertThat(PersonalInfoMasker.mask(input)).isEqualTo(expected);
	}

	@ParameterizedTest
	@CsvSource(delimiter = '|', value = {
			"010-****-5678 번호예요",
			"900101-******* 이에요",
			"****-****-****-3456 등록",
			"m***@naver.com 으로 보내 주세요"
	})
	@DisplayName("이미 마스킹된 질문은 다시 마스킹해도 그대로다")
	void keepsMaskedText(String masked) {
		assertThat(PersonalInfoMasker.mask(masked)).isEqualTo(masked);
	}
}
