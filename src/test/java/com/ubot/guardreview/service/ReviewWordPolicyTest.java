package com.ubot.guardreview.service;

import static org.assertj.core.api.Assertions.*;
import static com.ubot.guardreview.model.ReviewModels.*;
import com.ubot.guardreview.exception.ReviewException;
import com.ubot.guardreview.exception.ReviewErrorCode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class ReviewWordPolicyTest {
	@ParameterizedTest
	@ValueSource(strings = {"요금", "가", "요 금", "요\u200B금", "요금!!!", "ＡＢ", "𐐀𐐁", "요금", "！！！", "12"})
	void 공백과_기호와_UTF16길이로_짧은_후보의_제한을_우회하지못한다(String text) {
		assertThat(ReviewWordPolicy.eligible(text)).isFalse();
		assertThatThrownBy(() -> ReviewWordPolicy.requireEligible(text)).isInstanceOfSatisfying(ReviewException.class,
				e -> assertThat(e.getErrorCode()).isEqualTo(ReviewErrorCode.WORD_TOO_SHORT));
	}
	@Test
	void 세글자는_최소자격일뿐_위험도나_검사통과를_판정하지않는다() {
		assertThat(ReviewWordPolicy.eligible("요금제")).isTrue();
		assertThat(ReviewWordPolicy.eligible("ＡＢＣ")).isTrue();
	}
	@Test
	void 짧은_WORD를_경고로_보존하고_원문과_검토근거는_변경하지않는다() {
		var original = new Suggestion(Kind.WORD, "ABUSE", "요금", "문맥 검토 필요", Stage.INPUT, "HARMFUL_REQUEST");
		var warning = ReviewWordPolicy.forReview(original);
		assertThat(warning).isEqualTo(new Suggestion(Kind.WARNING, original.category(), original.expression(),
				original.reason(), original.stage(), original.intent()));
		assertThat(ReviewWordPolicy.forReview(warning)).isSameAs(warning);
	}
	@Test
	void 충분한_WORD와_기존_WARNING은_유형을_바꾸지않는다() {
		var word = new Suggestion(Kind.WORD, "ABUSE", "검토표현", "검토", Stage.INPUT, "HARMFUL_REQUEST");
		var warning = new Suggestion(Kind.WARNING, "ABUSE", "가", "검토", Stage.INPUT, "HARMFUL_REQUEST");
		assertThat(ReviewWordPolicy.forReview(word)).isSameAs(word);
		assertThat(ReviewWordPolicy.forReview(warning)).isSameAs(warning);
	}
}
