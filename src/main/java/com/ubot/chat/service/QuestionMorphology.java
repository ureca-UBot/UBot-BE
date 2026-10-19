package com.ubot.chat.service;

import java.util.ArrayList;
import java.util.List;
import kr.pe.bab2min.Kiwi;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 질문 정리 단계(경로 분류·원문 조각·가드·멀티턴 판정)가 함께 쓰는 Kiwi 형태소 분석입니다.
 * 어절 단위 분석은 어절마다 따로 분석해, 같은 어절이면 어느 문장에 있든 같은 결과가 나오게 합니다.
 */
@Component
@RequiredArgsConstructor
class QuestionMorphology {
	private static final Kiwi.AnalyzeOption ANALYZE_OPTION = new Kiwi.AnalyzeOption(Kiwi.Match.allWithNormalizing);
	/** 어절 앞뒤에서 떼어 내는 문장 부호입니다. 숫자 안의 쉼표·점은 떼지 않습니다. */
	private static final String EDGE_PUNCTUATION = "?？!！.,。~…'\"()<>{}[]";

	private final Kiwi kiwi;

	/** 형태소 하나입니다. form은 Kiwi가 정규화한 형태라 원문과 다를 수 있어, 원문에서 잘라 낸 surface를 함께 둡니다. */
	record Morph(String form, String tag, int start, int end, String surface) {
		boolean isPredicate() {
			return tag.startsWith("VV") || tag.startsWith("VA");
		}
	}

	/** 공백으로 나눈 어절입니다. key는 앞뒤 문장 부호를 뗀 형태이고, morphs는 key를 분석한 결과입니다. */
	record Word(String text, String key, List<Morph> morphs) {
		Morph last() {
			return morphs.isEmpty() ? null : morphs.getLast();
		}
	}

	List<Morph> analyze(String text) {
		List<Morph> morphs = new ArrayList<>();
		if (text == null || text.isBlank()) {
			return morphs;
		}
		Kiwi.Token[] tokens;
		try {
			tokens = kiwi.tokenize(text, ANALYZE_OPTION);
		} catch (Exception exception) {
			// 네이티브 코드는 선언하지 않은 checked 예외도 던지므로 Exception으로 받아 한 가지 예외로 바꿉니다.
			throw new MorphologyException(exception);
		}
		for (Kiwi.Token token : tokens) {
			int start = Math.min(token.position, text.length());
			int end = Math.min(start + token.length, text.length());
			morphs.add(new Morph(token.form, Kiwi.POSTag.toString(token.tag), start, end, text.substring(start, end)));
		}
		return morphs;
	}

	/** 공백으로 나눈 어절마다 분석합니다. 문장 부호만 있는 어절도 목록에 남기되 형태소는 비어 있습니다. */
	List<Word> words(String text) {
		List<Word> words = new ArrayList<>();
		if (text == null) {
			return words;
		}
		for (String token : text.strip().split("\\s+")) {
			if (token.isEmpty()) {
				continue;
			}
			String key = stripEdges(token);
			words.add(new Word(token, key, key.isEmpty() ? List.of() : analyze(key)));
		}
		return words;
	}

	static String stripEdges(String word) {
		int start = 0;
		int end = word.length();
		while (start < end && EDGE_PUNCTUATION.indexOf(word.charAt(start)) >= 0) {
			start++;
		}
		while (end > start && EDGE_PUNCTUATION.indexOf(word.charAt(end - 1)) >= 0) {
			end--;
		}
		return word.substring(start, end);
	}
}
