package com.ubot.guardreview.service;

import com.ubot.counsel.safety.util.SafetyTextNormalizer;
import com.ubot.guardreview.exception.ReviewErrorCode;
import com.ubot.guardreview.exception.ReviewException;
import com.ubot.guardreview.model.ReviewModels.Kind;
import com.ubot.guardreview.model.ReviewModels.Suggestion;

/** AI 검토 후보 경로의 최소 적용 자격입니다. 기존 수동 금칙어의 정책은 바꾸지 않습니다. */
public final class ReviewWordPolicy {
	private static final int MIN_WORD_CHARACTERS = 3;
	private ReviewWordPolicy() {
	}
	public static boolean eligible(String expression) {
		if (expression == null)
			return false;
		var significant = new StringBuilder();
		SafetyTextNormalizer.normalize(expression).codePoints().filter(Character::isLetterOrDigit)
				.forEach(significant::appendCodePoint);
		// 공백·숨은 문자로 분리된 자모도 합친 뒤 셉니다. UTF-16 길이와 문장부호로 길이를 늘리지 않습니다.
		return SafetyTextNormalizer.normalize(significant.toString()).codePoints().filter(Character::isLetterOrDigit)
				.count() >= MIN_WORD_CHARACTERS;
	}
	public static void requireEligible(String expression) {
		if (!eligible(expression))
			throw new ReviewException(ReviewErrorCode.WORD_TOO_SHORT);
	}
	public static Suggestion forReview(Suggestion suggestion) {
		if (suggestion.kind() != Kind.WORD || eligible(suggestion.expression()))
			return suggestion;
		// 짧은 표현도 발견 사례와 기존 경고 묶기에 남기되 금칙어 적용 후보로 등록하지 않습니다.
		return new Suggestion(Kind.WARNING, suggestion.category(), suggestion.expression(), suggestion.reason(),
				suggestion.stage(), suggestion.intent());
	}
}
