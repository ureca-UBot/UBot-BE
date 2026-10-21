package com.ubot.monitoring.faqmonitoring.format;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ubot.llm.exception.LlmErrorCode;
import com.ubot.llm.exception.LlmException;
import com.ubot.monitoring.faqmonitoring.dto.model.improvelayer.DuplicateReviewResult;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class DuplicateSuspectedReviewFormatTest {

	@Test
	@DisplayName("후보 FAQ별 중복 판정과 판단 근거를 구조화 응답에서 파싱한다")
	void 후보별_판정과_근거를_파싱한다() {
		var assessment = DuplicateSuspectedReviewFormat.parse("""
				```json
				{
				  "candidateAssessments": [
				    {"candidateFaqId": 10, "result": "DUPLICATE", "reason": "답변 범위가 같습니다."},
				    {"candidateFaqId": 20, "result": "NOT_DUPLICATE", "reason": "서로 다른 요구입니다."}
				  ]
				}
				```
				""", List.of(10L, 20L));

		assertThat(assessment.candidateAssessments()).hasSize(2);
		assertThat(assessment.candidateAssessments().getFirst().candidateFaqId()).isEqualTo(10L);
		assertThat(assessment.candidateAssessments().getFirst().result())
				.isEqualTo(DuplicateReviewResult.DUPLICATE);
	}

	@Test
	@DisplayName("후보 ID가 모두 일치하면 요청 순서와 다른 응답 순서도 허용한다")
	void 요청한_후보의_순서와_응답_순서가_달라도_허용한다() {
		var assessment = DuplicateSuspectedReviewFormat.parse("""
				{"candidateAssessments":[
				  {"candidateFaqId":20,"result":"NEEDS_DISTINCTION","reason":"범위를 구분해야 합니다."},
				  {"candidateFaqId":10,"result":"INSUFFICIENT_EVIDENCE","reason":"근거가 부족합니다."}
				]}
				""", List.of(10L, 20L));

		assertThat(assessment.candidateAssessments())
				.extracting(candidate -> candidate.candidateFaqId())
				.containsExactly(20L, 10L);
	}

	@ParameterizedTest
	@DisplayName("누락되거나 요청하지 않은 후보가 포함된 LLM 응답을 거절한다")
	@ValueSource(strings = {
			"",
			"판정 결과입니다.",
			"{}",
			"{\"candidateAssessments\":[]}",
			"{\"candidateAssessments\":[{\"candidateFaqId\":10,\"result\":\"DUPLICATE\",\"reason\":\" \"}]}",
			"{\"candidateAssessments\":[{\"candidateFaqId\":99,\"result\":\"DUPLICATE\",\"reason\":\"중복\"}]}"
	})
	void 누락되거나_잘못된_후보_응답을_거절한다(String output) {
		assertInvalid(() -> DuplicateSuspectedReviewFormat.parse(output, List.of(10L)));
	}

	@Test
	@DisplayName("동일한 후보 FAQ를 두 번 반환한 LLM 응답을 거절한다")
	void 같은_후보를_두_번_반환하면_거절한다() {
		String output = """
				{"candidateAssessments":[
				  {"candidateFaqId":10,"result":"DUPLICATE","reason":"중복입니다."},
				  {"candidateFaqId":10,"result":"NOT_DUPLICATE","reason":"별개입니다."}
				]}
				""";

		assertInvalid(() -> DuplicateSuspectedReviewFormat.parse(output, List.of(10L, 20L)));
	}

	@Test
	@DisplayName("중복 검토 JSON 스키마는 후보 ID와 판정 및 근거를 필수로 요구한다")
	void 스키마는_후보_ID_판정_근거를_모두_요구한다() {
		Map<String, Object> schema = DuplicateSuspectedReviewFormat.schema();
		Map<?, ?> properties = (Map<?, ?>) schema.get("properties");
		Map<?, ?> candidates = (Map<?, ?>) properties.get("candidateAssessments");
		Map<?, ?> candidateSchema = (Map<?, ?>) candidates.get("items");

		assertThat(candidateSchema.get("required"))
				.isEqualTo(List.of("candidateFaqId", "result", "reason"));
		assertThat(candidateSchema.get("additionalProperties")).isEqualTo(false);
	}

	private void assertInvalid(org.assertj.core.api.ThrowableAssert.ThrowingCallable callable) {
		assertThatThrownBy(callable)
				.isInstanceOfSatisfying(LlmException.class,
						exception -> assertThat(exception.getErrorCode())
								.isEqualTo(LlmErrorCode.LLM_RESPONSE_INVALID));
	}
}
