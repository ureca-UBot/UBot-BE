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
		return new Candidate(1, Kind.WORD, "ABUSE", text, "검토", "HARMFUL_OUTPUT", 1, "PENDING", null, null, 0, 0);
	}
	@Test
	void 신고에_쓰인_일반단어는_입력_출력_상담_오차단으로_발견한다() {
		List<GoldenCase> cases = List.of(
				new GoldenCase("report", "REPORT", Stage.INPUT, "욕설 문자를 받았어요. 신고 방법을 알려주세요.", List.of("욕설 문자를 받았어요"),
						List.of()),
				new GoldenCase("answer", "REPORT", Stage.OUTPUT, "욕설 문자는 증거를 보관하고 신고해 주세요.",
						List.of("욕설 문자는 증거를 보관하고 신고해 주세요"), List.of()),
				new GoldenCase("counsel", "REPORT", Stage.COUNSEL, "욕설 문자 피해를 신고하려고 합니다.",
						List.of("욕설 문자 피해를 신고하려고 합니다"), List.of()));
		Validation result = service.validate(List.of(candidate("욕설 문자")), Set.of(), cases, "cases-v1");
		assertThat(result.falsePositives()).isEqualTo(3);
		assertThat(result.passed()).isFalse();
		assertThat(result.cases())
				.allMatch(c -> c.falsePositive() && !c.before().isBlank() && !c.after().contains("욕설"));
		// 입력 검사에서 후보가 검출되면 질문 전체가 차단되므로 남은 문장은 없습니다.
		assertThat(result.cases().getFirst().after()).isEmpty();
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
		var result = service.validate(List.of(candidate("검증용모욕표현"), candidate("명의도용")), Set.of(), cases, "v1");
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
	@Test
	void 소유권을_잃은_검증은_다음_사례를_계산하지않는다() {
		var cases = java.util.Collections.nCopies(5168,
				new GoldenCase("safe", "NORMAL", Stage.COUNSEL, "정상 문의", List.of("정상 문의"), List.of()));
		var checks = new java.util.concurrent.atomic.AtomicInteger();
		assertThatThrownBy(() -> service.validate(List.of(candidate("실험표현")), Set.of(), cases, "v", () -> {
			if (checks.incrementAndGet() == 3)
				throw new com.ubot.guardreview.exception.ReviewLeaseLostException();
		})).isInstanceOf(com.ubot.guardreview.exception.ReviewLeaseLostException.class);
		assertThat(checks.get()).isEqualTo(3);
	}
	@Test
	void 전체5168건의_단일후보와_최대50후보_검증시간을_측정한다() throws Exception {
		String[] baseline;
		try (var input = new org.springframework.core.io.ClassPathResource("guard-review/baseline-words.json")
				.getInputStream()) {
			baseline = JsonMapper.builder().build().readValue(input.readAllBytes(), String[].class);
		}
		var cases = service.dataset();
		assertThat(cases).hasSize(5168);
		for (int size : List.of(1, 50)) {
			var candidates = java.util.stream.IntStream.range(0, size).mapToObj(i -> candidate("독립성능시험후보" + i))
					.toList();
			long started = System.nanoTime();
			Validation result = service.validate(candidates, Set.of(baseline), cases, service.datasetVersion());
			long elapsedMillis = java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
			System.out.println("GUARD_REVIEW_VALIDATION_TIMING candidates=" + size + " cases=" + cases.size()
					+ " elapsedMillis=" + elapsedMillis);
			assertThat(result.cases()).hasSize(5168);
			assertThat(result.falsePositives()).isZero();
			assertThat(result.regressions()).isZero();
		}
	}

	@Test
	void 같은_규칙과_금칙어는_순서나_서비스_인스턴스가_달라도_같은_버전이다() {
		var apiWords = new java.util.LinkedHashSet<>(List.of("표현B", "표현A"));
		var workerWords = new java.util.LinkedHashSet<>(List.of("표현A", "표현B"));
		var worker = new ReviewValidationService(mock(ForbiddenWordRepository.class), filter,
				new ChatPromptInjectionService());
		assertThat(service.baseVersion(apiWords)).isEqualTo(worker.baseVersion(workerWords));
		assertThat(service.baseVersion(Set.of())).isEqualTo(worker.baseVersion(Set.of()));
		verifyNoInteractions(words);
	}
	@Test
	void 활성_표현의_변경과_표현_사이의_경계는_검사_버전을_구분한다() {
		assertThat(service.baseVersion(Set.of("표현A"))).isNotEqualTo(service.baseVersion(Set.of("표현B")));
		assertThat(service.baseVersion(Set.of("표현A"))).isNotEqualTo(service.baseVersion(Set.of("표현A", "표현B")));
		assertThat(service.baseVersion(Set.of("표현A\n표현B"))).isNotEqualTo(service.baseVersion(Set.of("표현A", "표현B")));
	}

	@Test
	void 골든사례에_등장하지않아도_짧은_신규후보는_검사를_통과할수없다() {
		var cases = List.of(new GoldenCase("other", "NORMAL", Stage.COUNSEL, "상품 안내", List.of("상품 안내"), List.of()));
		assertThatThrownBy(() -> service.validate(List.of(candidate("요금")), Set.of(), cases, "v"))
				.isInstanceOfSatisfying(com.ubot.guardreview.exception.ReviewException.class,
						e -> assertThat(e.getErrorCode())
								.isEqualTo(com.ubot.guardreview.exception.ReviewErrorCode.WORD_TOO_SHORT));
	}

}
