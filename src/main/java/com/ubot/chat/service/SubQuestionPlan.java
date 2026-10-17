package com.ubot.chat.service;

import com.ubot.ai.dto.ConversationTurn;
import java.util.List;

/**
 * 한 턴에서 검색할 하위 질문 목록입니다. 목록 순서가 하위 질문 순번(sub_question_index)입니다.
 * 원문을 그대로 쓰는 경우도 하위 질문이 1개인 목록입니다.
 * <ul>
 *   <li>items: 하위 질문마다 검색어(searchQuery)와 그에 해당하는 사용자 원문 조각(userText)</li>
 *   <li>history: 이 턴 앞의 이전 대화. multiTurn이면 답변 LLM에도 넘깁니다.</li>
 *   <li>route: 경로 분류 결과. searchFirst면 판정 LLM 없이 원문으로 먼저 검색하고, 실패하면 판정합니다.</li>
 * </ul>
 */
record SubQuestionPlan(
		List<SubQuestion> items,
		Source source,
		List<ConversationTurn> history,
		boolean multiTurn,
		QuestionRouter.Route route,
		boolean searchFirst) {

	/** 한 턴에 검색할 수 있는 하위 질문 수입니다. 넘으면 하나씩 물어 달라고 안내합니다. DB의 CHECK 제약과 같습니다. */
	static final int MAX_SUB_QUESTIONS = 3;

	/**
	 * 하위 질문 하나입니다. searchQuery는 검색에 쓰고, userText는 답변 LLM에 하위 질문 제목으로 보여 줍니다.
	 * 질문을 나눴지만 원문을 자를 근거가 없었으면 userText는 null입니다.
	 */
	record SubQuestion(String searchQuery, String userText) {
	}

	enum Source {
		/** 판정 LLM이 정리했습니다. */
		LLM,
		/** 판정을 거치지 않고 원문을 썼습니다. */
		ORIGINAL,
		/** 경로 분류에서 판정할 표지가 없어 원문으로 먼저 검색합니다. */
		ORIGINAL_SEARCH_FIRST,
		/** 판정 LLM 호출이나 응답 해석에 실패해 원문을 썼습니다. */
		LLM_FAILED,
		/** 판정 LLM의 하위 질문을 가드가 모두 버려 원문으로 돌렸습니다. */
		GUARD_FALLBACK,
		/** 의도 재검색이라 원본 시도의 하위 질문을 다시 썼습니다. */
		REUSED
	}

	SubQuestionPlan {
		items = List.copyOf(items);
		history = history == null ? List.of() : List.copyOf(history);
		route = route == null ? QuestionRouter.Route.none() : route;
		if (items.isEmpty()) {
			throw new IllegalArgumentException("하위 질문은 하나 이상이어야 합니다.");
		}
	}

	/** 검색어와 원문 조각이 같은 하위 질문들로 만듭니다. 이전 대화는 쓰지 않습니다. */
	SubQuestionPlan(List<String> searchQueries, Source source) {
		this(searchQueries.stream().map(searchQuery -> new SubQuestion(searchQuery, searchQuery)).toList(),
				source, List.of(), false, null, false);
	}

	static SubQuestionPlan original(String question, Source source) {
		return new SubQuestionPlan(List.of(question), source);
	}

	static SubQuestionPlan searchFirst(String question, List<ConversationTurn> history, QuestionRouter.Route route) {
		return new SubQuestionPlan(List.of(new SubQuestion(question, question)), Source.ORIGINAL_SEARCH_FIRST,
				history, false, route, true);
	}

	/** 검색어 목록입니다. */
	List<String> searchQueries() {
		return items.stream().map(SubQuestion::searchQuery).toList();
	}

	/** 답변 LLM에 넘길 이전 대화입니다. 멀티턴이 아니면 비어 있습니다. */
	List<ConversationTurn> historyForAnswer() {
		return multiTurn ? history : List.of();
	}

	boolean isTooMany() {
		return items.size() > MAX_SUB_QUESTIONS;
	}
}
