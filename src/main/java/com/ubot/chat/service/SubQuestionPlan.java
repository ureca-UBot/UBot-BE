package com.ubot.chat.service;

import java.util.List;

/**
 * 한 턴에서 검색할 하위 질문 목록입니다. 목록 순서가 하위 질문 순번(sub_question_index)입니다.
 * 원문을 그대로 쓰는 경우도 하위 질문이 1개인 목록입니다.
 */
record SubQuestionPlan(List<String> subQuestions, boolean contextUsed, Source source) {

	/** 한 턴에 검색할 수 있는 하위 질문 수입니다. 넘으면 하나씩 물어 달라고 안내합니다. DB의 CHECK 제약과 같습니다. */
	static final int MAX_SUB_QUESTIONS = 3;

	enum Source {
		/** 판정 LLM이 정리했습니다. */
		LLM,
		/** 판정을 거치지 않고 원문을 썼습니다. */
		ORIGINAL,
		/** 판정 LLM 호출이나 응답 해석에 실패해 원문을 썼습니다. */
		LLM_FAILED,
		/** 의도 재검색이라 원본 시도의 하위 질문을 다시 썼습니다. */
		REUSED
	}

	SubQuestionPlan {
		subQuestions = List.copyOf(subQuestions);
		if (subQuestions.isEmpty()) {
			throw new IllegalArgumentException("하위 질문은 하나 이상이어야 합니다.");
		}
	}

	static SubQuestionPlan original(String question, Source source) {
		return new SubQuestionPlan(List.of(question), false, source);
	}

	boolean isTooMany() {
		return subQuestions.size() > MAX_SUB_QUESTIONS;
	}
}
