package com.ubot.guardreview.service;

import static com.ubot.guardreview.model.ReviewModels.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.ubot.chat.service.ChatPromptInjectionService;
import com.ubot.forbiddenword.repository.ForbiddenWordRepository;
import com.ubot.forbiddenword.service.ForbiddenWordFilterService;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class ReviewValidationServiceTest {
	ForbiddenWordRepository words = mock(ForbiddenWordRepository.class);
	ForbiddenWordFilterService filter = new ForbiddenWordFilterService(words);
	ReviewValidationService service = new ReviewValidationService(words, filter, new ChatPromptInjectionService());
	Candidate candidate(String text) {
		return new Candidate(1, Kind.WORD, "ABUSE", text, "검토", "HARMFUL_OUTPUT", 1, "PENDING", null, null, null, 0, 0);
	}
	@Test
	void 신고에_쓰인_일반단어는_입력_출력_상담_오차단으로_발견한다() {
		List<GoldenCase> cases = List.of(
				new GoldenCase("report", "REPORT", Stage.INPUT, "욕설 문자를 받았어요. 신고 방법을 알려주세요.", List.of("욕설 문자를 받았어요"),
						List.of()),
				new GoldenCase("answer", "REPORT", Stage.OUTPUT, "욕설 문자는 증거를 보관하고 신고해 주세요.",
						List.of("욕설 문자는 증거를 보관하고 신고해 주세요"), List.of()),
				new GoldenCase("counsel", "REPORT", Stage.COUNSEL, "욕설 피해를 신고하려고 합니다.", List.of("욕설 피해를 신고하려고 합니다"),
						List.of()));
		Validation result = service.validate(List.of(candidate("욕설")), Set.of(), cases, "cases-v1");
		assertThat(result.falsePositives()).isEqualTo(3);
		assertThat(result.passed()).isFalse();
		assertThat(result.cases())
				.allMatch(c -> c.falsePositive() && !c.before().isBlank() && !c.after().contains("욕설"));
		assertThat(result.cases().getFirst().after()).contains("신고 방법을 알려주세요");
	}
	@Test
	void 독립_검사본은_운영캐시를_바꾸지않는다() {
		var preview = filter.preview(Set.of("검증용모욕표현"));
		assertThatThrownBy(() -> preview.validateForbiddenWord("검증용모욕표현"))
				.isInstanceOf(com.ubot.common.GlobalException.class);
		assertThatCode(() -> filter.validateForbiddenWord("검증용모욕표현")).doesNotThrowAnyException();
		verifyNoInteractions(words);
	}
	@Test
	void 기존_위험요구_제거와_정상_질문을_함께_확인한다() {
		var cases = List.of(
				new GoldenCase("safe", "NORMAL", Stage.INPUT, "요금제를 알려주세요.", List.of("요금제를 알려주세요"), List.of()),
				new GoldenCase("unsafe", "ABUSE", Stage.COUNSEL, "검증용모욕표현", List.of(), List.of("검증용모욕표현")));
		var result = service.validate(List.of(candidate("검증용모욕표현")), Set.of(), cases, "gold-v1");
		assertThat(result.passed()).isTrue();
		assertThat(result.newDetections()).isEqualTo(1);
	}
	@Test
	void 후보_묶음_전체를_검사해서_하나라도_오차단하면_실패한다() {
		var cases = List
				.of(new GoldenCase("safe", "NORMAL", Stage.COUNSEL, "명의도용 피해 신고", List.of("명의도용 피해 신고"), List.of()));
		var result = service.validate(List.of(candidate("검증용모욕표현"), candidate("명의")), Set.of(), cases, "v1");
		assertThat(result.falsePositives()).isEqualTo(1);
	}
	@Test
	void 예전부터_있던_오차단은_새_오차단과_구분한다() {
		var cases = List
				.of(new GoldenCase("safe", "NORMAL", Stage.COUNSEL, "명의도용 피해 신고", List.of("명의도용 피해 신고"), List.of()));
		var result = service.validate(List.of(candidate("검증용모욕표현")), Set.of("명의"), cases, "v1");
		assertThat(result.falsePositives()).isZero();
		assertThat(result.baselineFailures()).isEqualTo(1);
	}
	@Test
	void 기존_시험자료_전체를_읽고_추가금칙어의_영향을_비교한다() throws Exception {
		List<GoldenCase> cases = service.dataset();
		assertThat(cases).hasSize(5168);
		assertThat(cases.stream().filter(c -> c.stage() == Stage.OUTPUT && !c.text().isBlank()).count())
				.isGreaterThanOrEqualTo(2564);
		String[] baselineWords;
		try (var input = new org.springframework.core.io.ClassPathResource("guard-review/baseline-words.json")
				.getInputStream()) {
			baselineWords = JsonMapper.builder().build().readValue(input.readAllBytes(), String[].class);
		}
		Validation result = service.validate(List.of(candidate("검증용모욕표현")), Set.of(baselineWords), cases,
				service.datasetVersion());
		assertThat(result.falsePositives()).isZero();
		assertThat(result.regressions()).isZero();
		assertThat(result.passed()).isTrue();
	}
}
