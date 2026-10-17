package com.ubot.chat.service;

import com.ubot.chat.service.QuestionMorphology.Morph;
import com.ubot.chat.service.QuestionMorphology.Word;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 판정 LLM이 나눈 하위 질문에 맞춰 사용자 원문을 조각으로 자릅니다. 조각은 원문을 자른 것이라 판정 LLM의 변형이 들어가지 않으며,
 * 답변 LLM에 하위 질문 제목으로 보여 주고, 가드가 막은 하위 질문의 검색어로도 씁니다.
 * <ol>
 *   <li>하위 질문마다 그 하위 질문에만 있는 내용어를 구하고, 원문 어절의 내용어가 한 하위 질문의 것과만 겹치면 그 하위 질문에 붙입니다.</li>
 *   <li>붙은 어절이 원문 순서대로 1, 2, 3…으로 이어지고 모든 하위 질문에 어절이 있으면, 앞 하위 질문의 마지막 어절과 다음 하위 질문의
 *       첫 어절 사이에서 문장 부호·연결어미·접속조사로 끝나는 마지막 어절 뒤를 자릅니다.</li>
 *   <li>정렬이 안 되면, 원문의 문장 부호·쉼표·접속조사 경계가 정확히 하위 질문 수보다 하나 적을 때 그 경계에서 자릅니다.</li>
 * </ol>
 * 그래도 안 되면 빈 값입니다. 이때는 원문을 하나로 씁니다.
 */
@Component
@RequiredArgsConstructor
class OriginalQuestionSplitter {
	private static final Set<String> STRONG_TAGS = Set.of("NNG", "NNP", "SL", "SN", "SH", "SW", "XR");
	private static final Set<String> LIGHT_PREDICATES = Set.of("있", "없", "하", "되", "이", "아니", "같");
	private static final Set<String> JOINERS = Set.of("랑", "이랑", "하고", "와", "과");
	private static final String CUT_PUNCTUATION = "?？.!,，~";
	private static final String BOUNDARY_PUNCTUATION = "?？.!,，";

	private final QuestionMorphology morphology;

	/** 검색어(searchQueries)가 2개 이상일 때만 자릅니다. 결과 목록의 크기는 searchQueries와 같습니다. */
	Optional<List<String>> split(String question, List<String> searchQueries) {
		if (searchQueries.size() < 2) {
			return Optional.empty();
		}
		List<Word> words = morphology.words(question);
		Optional<List<String>> aligned = align(words, searchQueries);
		return aligned.isPresent() ? aligned : byBoundaries(words, searchQueries.size());
	}

	/** 정렬 실패를 판정 LLM 변형의 신호로도 씁니다(하위 질문 순서가 원문과 엇갈림). */
	private Optional<List<String>> align(List<Word> words, List<String> searchQueries) {
		List<Set<String>> subForms = searchQueries.stream().map(this::strongForms).toList();
		List<Set<String>> unique = new ArrayList<>();
		for (int i = 0; i < subForms.size(); i++) {
			Set<String> own = new HashSet<>(subForms.get(i));
			for (int j = 0; j < subForms.size(); j++) {
				if (j != i) {
					own.removeAll(subForms.get(j));
				}
			}
			unique.add(own);
		}
		List<Integer> owners = new ArrayList<>();
		for (Word word : words) {
			Set<String> forms = strongForms(word);
			List<Integer> hits = new ArrayList<>();
			for (int i = 0; i < unique.size(); i++) {
				if (forms.stream().anyMatch(unique.get(i)::contains)) {
					hits.add(i);
				}
			}
			owners.add(hits.size() == 1 ? hits.getFirst() : null);
		}
		List<Integer> decided = owners.stream().filter(owner -> owner != null).toList();
		if (!decided.equals(decided.stream().sorted().toList()) || decided.stream().distinct().count() != searchQueries.size()) {
			return Optional.empty();
		}
		List<Integer> bounds = new ArrayList<>();
		bounds.add(0);
		for (int i = 0; i < searchQueries.size() - 1; i++) {
			int last = owners.lastIndexOf(i);
			int first = owners.indexOf(i + 1);
			int cut = first - 1;
			for (int c = first - 1; c >= last; c--) {
				if (isCut(words.get(c))) {
					cut = c;
					break;
				}
			}
			bounds.add(cut + 1);
		}
		bounds.add(words.size());
		return Optional.of(pieces(words, bounds));
	}

	private Optional<List<String>> byBoundaries(List<Word> words, int count) {
		List<Integer> bounds = new ArrayList<>();
		bounds.add(0);
		for (int i = 0; i < words.size() - 1; i++) {
			if (isBoundary(words.get(i))) {
				bounds.add(i + 1);
			}
		}
		if (bounds.size() != count) {
			return Optional.empty();
		}
		bounds.add(words.size());
		return Optional.of(pieces(words, bounds));
	}

	private static List<String> pieces(List<Word> words, List<Integer> bounds) {
		List<String> pieces = new ArrayList<>();
		for (int i = 0; i < bounds.size() - 1; i++) {
			pieces.add(String.join(" ", words.subList(bounds.get(i), bounds.get(i + 1)).stream().map(Word::text).toList()));
		}
		return pieces;
	}

	/** 소속을 정할 때 쓰는 내용어입니다. 의존명사·대명사·가벼운 용언은 어느 질문에나 나와 빼고 봅니다. */
	private Set<String> strongForms(String text) {
		Set<String> forms = new HashSet<>();
		morphology.words(text).forEach(word -> forms.addAll(strongForms(word)));
		return forms;
	}

	private static Set<String> strongForms(Word word) {
		Set<String> forms = new HashSet<>();
		for (Morph morph : word.morphs()) {
			if (STRONG_TAGS.contains(morph.tag()) || (morph.isPredicate() && !LIGHT_PREDICATES.contains(morph.form()))) {
				forms.add(morph.form());
			}
		}
		return forms;
	}

	/** 이 어절 뒤에서 자를 수 있는지 봅니다. 문장 부호, 연결어미, 접속조사로 끝나는 어절입니다. */
	private static boolean isCut(Word word) {
		if (CUT_PUNCTUATION.indexOf(word.text().charAt(word.text().length() - 1)) >= 0) {
			return true;
		}
		Morph last = word.last();
		return last != null && (last.tag().startsWith("SF") || last.tag().startsWith("SP") || last.tag().startsWith("EC")
				|| last.tag().startsWith("JC") || JOINERS.contains(last.surface()));
	}

	/** 정렬이 안 될 때 쓰는 경계입니다. 연결어미는 한 질문 안에도 흔해 빼고, 문장 부호·쉼표·접속조사만 봅니다. */
	private static boolean isBoundary(Word word) {
		if (BOUNDARY_PUNCTUATION.indexOf(word.text().charAt(word.text().length() - 1)) >= 0) {
			return true;
		}
		Morph last = word.last();
		return last != null && (last.tag().startsWith("JC") || JOINERS.contains(last.surface()));
	}
}
