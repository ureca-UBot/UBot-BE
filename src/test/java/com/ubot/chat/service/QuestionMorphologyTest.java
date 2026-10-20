package com.ubot.chat.service;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import kr.pe.bab2min.Kiwi;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("형태소 분석 테스트")
class QuestionMorphologyTest {

	@Test
	@DisplayName("Kiwi가 선언하지 않은 checked 예외를 던져도 MorphologyException 하나로 바꿔 던진다")
	void translatesNativeFailure() {
		Kiwi kiwi = mock(Kiwi.class);
		Exception nativeFailure = new Exception("모델 상태 이상");
		// 네이티브 코드는 throws 선언 없이 java.lang.Exception을 던질 수 있습니다.
		when(kiwi.tokenize(anyString(), any(Kiwi.AnalyzeOption.class))).thenAnswer(invocation -> {
			throw nativeFailure;
		});
		QuestionMorphology morphology = new QuestionMorphology(kiwi);

		assertThatThrownBy(() -> morphology.analyze("유심 재발급"))
				.isInstanceOf(MorphologyException.class)
				.hasCause(nativeFailure);
		assertThatThrownBy(() -> morphology.words("유심 재발급"))
				.isInstanceOf(MorphologyException.class)
				.hasCause(nativeFailure);
	}
}
