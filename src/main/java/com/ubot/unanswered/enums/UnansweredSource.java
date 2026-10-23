package com.ubot.unanswered.enums;

public enum UnansweredSource {
	RETRIEVAL,
	OUTPUT_GUARD,
	/** 답변 LLM이 하위 질문의 칸을 ABSTAIN·CONFLICT로 판단했습니다. */
	LLM_MODEL
}
