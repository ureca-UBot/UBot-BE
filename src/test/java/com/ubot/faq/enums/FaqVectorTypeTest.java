package com.ubot.faq.enums;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("FAQ 벡터 유형 테스트")
class FaqVectorTypeTest {

	@Test
	@DisplayName("QUESTION은 질문만 임베딩한다")
	void question_usesQuestionOnly() {
		assertThat(
				FaqVectorType.QUESTION.toEmbeddingText(
						"유심을 재발급하려면 어떻게 하나요?",
						"매장에서 재발급할 수 있습니다."
				)
		).isEqualTo("유심을 재발급하려면 어떻게 하나요?");
	}

	@Test
	@DisplayName("QUESTION_ANSWER는 질문과 답변을 한 문자열로 합쳐 임베딩한다")
	void questionAnswer_joinsQuestionAndAnswer() {
		assertThat(
				FaqVectorType.QUESTION_ANSWER.toEmbeddingText(
						"유심을 재발급하려면 어떻게 하나요?",
						"매장에서 재발급할 수 있습니다."
				)
		).isEqualTo(
				"질문: 유심을 재발급하려면 어떻게 하나요?\n답변: 매장에서 재발급할 수 있습니다."
		);
	}
}
