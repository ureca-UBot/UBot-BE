package com.ubot.chat.service;

import com.ubot.ai.dto.SubQuestionFaqs;
import com.ubot.faq.dto.response.FaqSearchResponseDto;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 하위 질문별 FAQ 묶음에서 공통 FAQ 목록을 만듭니다. 같은 FAQ는 한 번만 넣고, 하위 질문마다 답변 문장을 다르게 골랐으면
 * 고른 문장을 합칩니다. 합친 문장은 원본 답변의 순서를 따르고 겹치는 문장은 한 번만 넣으므로, 원본 답변에서 어느 하위 질문도
 * 고르지 않은 문장만 빠진 형태가 됩니다. 하위 질문별 묶음은 바꾸지 않습니다.
 */
final class CommonFaqMerger {

	private CommonFaqMerger() {
	}

	/**
	 * originals는 원본 답변을 담은 하위 질문별 FAQ, selected는 고른 답변 문장만 담은 하위 질문별 FAQ입니다.
	 * 둘은 하위 질문과 FAQ의 순서가 같아야 합니다. 결과는 처음 나온 순서(하위 질문 순서, 그 안의 유사도 순서)입니다.
	 */
	static List<FaqSearchResponseDto> merge(List<SubQuestionFaqs> originals, List<SubQuestionFaqs> selected) {
		Map<Long, String> originalAnswers = new LinkedHashMap<>();
		Map<Long, List<FaqSearchResponseDto>> versions = new LinkedHashMap<>();
		for (int index = 0; index < selected.size(); index++) {
			List<FaqSearchResponseDto> originalFaqs = originals.get(index).faqs();
			List<FaqSearchResponseDto> selectedFaqs = selected.get(index).faqs();
			for (int faqIndex = 0; faqIndex < selectedFaqs.size(); faqIndex++) {
				FaqSearchResponseDto faq = selectedFaqs.get(faqIndex);
				originalAnswers.putIfAbsent(faq.faqId(), originalFaqs.get(faqIndex).answer());
				versions.computeIfAbsent(faq.faqId(), key -> new ArrayList<>()).add(faq);
			}
		}
		return versions.entrySet().stream()
				.map(entry -> merge(originalAnswers.get(entry.getKey()), entry.getValue()))
				.toList();
	}

	private static FaqSearchResponseDto merge(String originalAnswer, List<FaqSearchResponseDto> versions) {
		FaqSearchResponseDto first = versions.getFirst();
		if (versions.stream().allMatch(version -> version.answer().equals(first.answer()))) {
			return first;
		}
		// 문장 필터는 고른 문장(목록은 줄마다)을 줄바꿈으로 이어 붙이므로, 줄 단위로 모아 원본 답변에서의 위치로 정렬합니다.
		Set<String> lines = new LinkedHashSet<>();
		versions.forEach(version -> version.answer().lines()
				.map(String::strip)
				.filter(line -> !line.isEmpty())
				.forEach(lines::add));
		String normalizedOriginal = normalize(originalAnswer);
		// 원본에서 찾지 못한 줄은 정렬이 안정적이므로 처음 나온 순서대로 뒤에 붙습니다.
		List<String> ordered = lines.stream()
				.sorted(Comparator.comparingInt(line -> positionOf(normalizedOriginal, line)))
				.toList();
		return new FaqSearchResponseDto(first.faqId(), first.question(), String.join("\n", ordered),
				first.similarityScore(), first.intent(), first.variantId(), first.variantQuestion(), first.cacheable());
	}

	private static int positionOf(String normalizedOriginal, String line) {
		int position = normalizedOriginal.indexOf(normalize(line));
		return position < 0 ? Integer.MAX_VALUE : position;
	}

	/** 문장 필터가 문장을 고를 때처럼 줄바꿈·들여쓰기를 공백 하나로 바꿉니다. */
	private static String normalize(String value) {
		return value == null ? "" : value.replaceAll("\\s+", " ").strip();
	}
}
