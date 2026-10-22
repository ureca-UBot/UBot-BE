package com.ubot.monitoring.faqmonitoring.format;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ubot.llm.exception.LlmErrorCode;
import com.ubot.llm.exception.LlmException;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class LowRagQualityReviewFormatTest {

	@Test
	@DisplayName("대표 질문 수정 필요 판정과 수정안을 구조화 응답에서 파싱한다")
	void 대표_질문_수정_판정을_파싱한다() {
		var assessment = LowRagQualityReviewFormat.parseRepresentative("""
				{"needsUpdate":true,"reason":"질문이 잔액 조회 범위를 드러내지 못합니다.",
				 "suggestedQuestion":"현재 사용 가능한 포인트는 얼마인가요?"}
				""");

		assertThat(assessment.needsUpdate()).isTrue();
		assertThat(assessment.suggestedQuestion()).isEqualTo("현재 사용 가능한 포인트는 얼마인가요?");
	}

	@Test
	@DisplayName("대표 질문 수정이 불필요하면 수정안은 해당 없음이어야 한다")
	void 대표_질문_수정이_불필요하면_수정안은_해당_없음이어야_한다() {
		var assessment = LowRagQualityReviewFormat.parseRepresentative("""
				{"needsUpdate":false,"reason":"질문이 답변 범위를 충분히 나타냅니다.","suggestedQuestion":"해당 없음"}
				""");

		assertThat(assessment.needsUpdate()).isFalse();
	}

	@ParameterizedTest
	@DisplayName("대표 질문 수정 판정과 수정안이 모순되는 LLM 응답을 거절한다")
	@ValueSource(strings = {
			"{\"needsUpdate\":true,\"reason\":\"근거\",\"suggestedQuestion\":\"해당 없음\"}",
			"{\"needsUpdate\":false,\"reason\":\"근거\",\"suggestedQuestion\":\"새 질문\"}",
			"{\"needsUpdate\":false,\"reason\":\"해당 없음\",\"suggestedQuestion\":\"해당 없음\"}",
			"{\"needsUpdate\":null,\"reason\":\"근거\",\"suggestedQuestion\":\"해당 없음\"}"
	})
	void 대표_질문_판정과_수정안이_모순되면_거절한다(String output) {
		assertInvalid(() -> LowRagQualityReviewFormat.parseRepresentative(output));
	}

	@Test
	@DisplayName("검색 질문별 번호와 현재 FAQ의 답변 가능 여부를 파싱한다")
	void 모든_질문_번호와_답변_가능_여부를_파싱한다() {
		var assessment = LowRagQualityReviewFormat.parseVariant("""
				{"questionAssessments":[
				  {"questionNumber":2,"answerableByCurrentFaq":false},
				  {"questionNumber":1,"answerableByCurrentFaq":true}
				]}
				""", 2);

		assertThat(assessment.questionAssessments()).hasSize(2);
		assertThat(assessment.questionAssessments())
				.anySatisfy(question -> {
					assertThat(question.questionNumber()).isEqualTo(1);
					assertThat(question.answerableByCurrentFaq()).isTrue();
				});
	}

	@ParameterizedTest
	@DisplayName("검색 질문 번호나 답변 가능 여부가 잘못된 LLM 응답을 거절한다")
	@ValueSource(strings = {
			"{\"questionAssessments\":[]}",
			"{\"questionAssessments\":[{\"questionNumber\":0,\"answerableByCurrentFaq\":true}]}",
			"{\"questionAssessments\":[{\"questionNumber\":2,\"answerableByCurrentFaq\":true}]}",
			"{\"questionAssessments\":[{\"questionNumber\":1,\"answerableByCurrentFaq\":null}]}"
	})
	void 질문_번호나_boolean이_잘못되면_거절한다(String output) {
		assertInvalid(() -> LowRagQualityReviewFormat.parseVariant(output, 1));
	}

	@Test
	@DisplayName("동일한 검색 질문 번호를 두 번 반환한 LLM 응답을 거절한다")
	void 같은_질문_번호를_두_번_반환하면_거절한다() {
		String output = """
				{"questionAssessments":[
				  {"questionNumber":1,"answerableByCurrentFaq":true},
				  {"questionNumber":1,"answerableByCurrentFaq":false}
				]}
				""";

		assertInvalid(() -> LowRagQualityReviewFormat.parseVariant(output, 2));
	}

	@Test
	@DisplayName("검색 품질 검토 JSON 스키마는 정의되지 않은 필드를 허용하지 않는다")
	void 스키마는_정의되지_않은_필드를_허용하지_않는다() {
		Map<String, Object> representative = LowRagQualityReviewFormat.representativeSchema();
		Map<String, Object> variant = LowRagQualityReviewFormat.variantSchema();

		assertThat(representative.get("required"))
				.isEqualTo(List.of("needsUpdate", "reason", "suggestedQuestion"));
		assertThat(representative.get("additionalProperties")).isEqualTo(false);
		assertThat(variant.get("additionalProperties")).isEqualTo(false);
	}

	private void assertInvalid(org.assertj.core.api.ThrowableAssert.ThrowingCallable callable) {
		assertThatThrownBy(callable)
				.isInstanceOfSatisfying(LlmException.class,
						exception -> assertThat(exception.getErrorCode())
								.isEqualTo(LlmErrorCode.LLM_RESPONSE_INVALID));
	}
}
