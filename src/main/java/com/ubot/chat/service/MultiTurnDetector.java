package com.ubot.chat.service;

import com.ubot.ai.dto.ConversationTurn;
import com.ubot.chat.service.QuestionMorphology.Morph;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 답변 LLM에 이전 대화를 넘길지 정합니다. 이전 대화를 항상 넘기면 이전 답변의 틀린 내용이 새 답변으로 옮겨 가므로,
 * 이번 질문이 이전 대화를 써야 하는 경우에만 넘깁니다. 이전 대화가 있을 때 아래 중 하나면 멀티턴으로 봅니다.
 * <ul>
 *   <li>판정 LLM의 검색어에 원문에는 없고 이전 대화에만 있는 명사·의문사·서술어가 들어감(맥락을 채웠음)</li>
 *   <li>검색어에 이전 대화를 가리키는 말이 남음(맥락이 필요한데 채우지 못했음)</li>
 *   <li>원문에 멀티턴 표지가 있음(QuestionRouter)</li>
 * </ul>
 */
@Component
@RequiredArgsConstructor
class MultiTurnDetector {
	private static final Set<String> CONTENT_TAGS = Set.of("NNG", "NNP", "SL", "SN", "XR");
	private static final Set<String> INTERROGATIVES =
			Set.of("얼마", "어떻", "어떤", "어디", "언제", "몇", "무엇", "뭐", "왜", "며칠", "누구", "무슨", "어느");
	private static final Set<String> LIGHT_PREDICATES = Set.of("있", "없", "하", "되", "이", "아니", "같");

	private final QuestionMorphology morphology;

	boolean isMultiTurn(String question, List<ConversationTurn> history, List<String> searchQueries, QuestionRouter.Route route) {
		if (history.isEmpty()) {
			return false;
		}
		if (route.hasMultiTurnMark()) {
			return true;
		}
		boolean pointerLeft = searchQueries.stream().flatMap(searchQuery -> morphology.words(searchQuery).stream())
				.anyMatch(word -> QuestionRouter.POINTERS.contains(word.key()));
		if (pointerLeft) {
			return true;
		}
		Set<String> borrowed = new HashSet<>();
		searchQueries.forEach(searchQuery -> borrowed.addAll(forms(searchQuery)));
		borrowed.removeAll(forms(question));
		if (borrowed.isEmpty()) {
			return false;
		}
		Set<String> historyForms = new HashSet<>();
		for (ConversationTurn turn : history) {
			historyForms.addAll(forms(turn.question()));
			if (turn.answer() != null) {
				historyForms.addAll(forms(turn.answer()));
			}
		}
		return borrowed.stream().anyMatch(historyForms::contains);
	}

	private Set<String> forms(String text) {
		Set<String> forms = new HashSet<>();
		morphology.words(text).forEach(word -> {
			for (Morph morph : word.morphs()) {
				if (CONTENT_TAGS.contains(morph.tag()) || INTERROGATIVES.contains(morph.form())
						|| (morph.isPredicate() && !LIGHT_PREDICATES.contains(morph.form()))) {
					forms.add(morph.form());
				}
			}
		});
		return forms;
	}
}
