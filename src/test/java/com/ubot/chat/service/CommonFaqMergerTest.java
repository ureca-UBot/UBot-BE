package com.ubot.chat.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.ubot.ai.dto.SubQuestionFaqs;
import com.ubot.faq.dto.response.FaqSearchResponseDto;
import com.ubot.faq.enums.Intent;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("공통 FAQ 목록 합치기")
class CommonFaqMergerTest {
	private static final String ORIGINAL = """
			5G 프리미어 요금제는 월 85,000원입니다. 선택약정 시 25% 할인됩니다.
			데이터는 매월 무제한으로 제공됩니다.
			준비물은 다음과 같습니다:
			  - 신분증
			  - 기존 유심""";

	@Test
	@DisplayName("같은 FAQ에서 하위 질문마다 고른 문장을 원본 답변 순서대로, 겹치는 문장은 한 번만 합친다")
	void mergesSelectedSentencesInOriginalOrder() {
		// 두 번째 하위 질문이 앞쪽 문장을 골랐어도 원본 순서(가격 → 데이터 → 목록)를 따릅니다.
		List<SubQuestionFaqs> originals = List.of(
				new SubQuestionFaqs("데이터", List.of(faq(10L, ORIGINAL), faq(11L, "다른 답변")), "데이터"),
				new SubQuestionFaqs("가격", List.of(faq(10L, ORIGINAL)), "가격"));
		List<SubQuestionFaqs> selected = List.of(
				new SubQuestionFaqs("데이터", List.of(
						faq(10L, "데이터는 매월 무제한으로 제공됩니다.\n준비물은 다음과 같습니다:\n- 신분증\n- 기존 유심"),
						faq(11L, "다른 답변")), "데이터"),
				new SubQuestionFaqs("가격", List.of(
						faq(10L, "5G 프리미어 요금제는 월 85,000원입니다.\n데이터는 매월 무제한으로 제공됩니다.")), "가격"));

		List<FaqSearchResponseDto> merged = CommonFaqMerger.merge(originals, selected);

		assertThat(merged).extracting(FaqSearchResponseDto::faqId).containsExactly(10L, 11L);
		assertThat(merged.getFirst().answer()).isEqualTo("""
				5G 프리미어 요금제는 월 85,000원입니다.
				데이터는 매월 무제한으로 제공됩니다.
				준비물은 다음과 같습니다:
				- 신분증
				- 기존 유심""");
		assertThat(merged.get(1)).isEqualTo(selected.getFirst().faqs().get(1));
	}

	@Test
	@DisplayName("하위 질문마다 고른 문장이 같으면 그 FAQ를 그대로 한 번만 넣는다")
	void keepsIdenticalVersionsAsIs() {
		FaqSearchResponseDto chosen = faq(10L, "데이터는 매월 무제한으로 제공됩니다.");
		List<SubQuestionFaqs> originals = List.of(
				new SubQuestionFaqs("a", List.of(faq(10L, ORIGINAL)), "a"),
				new SubQuestionFaqs("b", List.of(faq(10L, ORIGINAL)), "b"));
		List<SubQuestionFaqs> selected = List.of(
				new SubQuestionFaqs("a", List.of(chosen), "a"),
				new SubQuestionFaqs("b", List.of(faq(10L, "데이터는 매월 무제한으로 제공됩니다.")), "b"));

		assertThat(CommonFaqMerger.merge(originals, selected)).containsExactly(chosen);
	}

	@Test
	@DisplayName("근거가 없는 하위 질문은 건너뛴다")
	void skipsSubQuestionsWithoutFaqs() {
		FaqSearchResponseDto chosen = faq(10L, "데이터는 매월 무제한으로 제공됩니다.");
		List<SubQuestionFaqs> originals = List.of(
				new SubQuestionFaqs("a", List.of(), "a"),
				new SubQuestionFaqs("b", List.of(faq(10L, ORIGINAL)), "b"));
		List<SubQuestionFaqs> selected = List.of(
				new SubQuestionFaqs("a", List.of(), "a"),
				new SubQuestionFaqs("b", List.of(chosen), "b"));

		assertThat(CommonFaqMerger.merge(originals, selected)).containsExactly(chosen);
	}

	private FaqSearchResponseDto faq(Long id, String answer) {
		return new FaqSearchResponseDto(id, "질문 " + id, answer, 0.9, Intent.GENERAL);
	}
}
