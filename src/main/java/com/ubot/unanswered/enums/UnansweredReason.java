package com.ubot.unanswered.enums;

public enum UnansweredReason {
	NO_FAQ,
	INSUFFICIENT_FAQ,
	LLM_ABSTAIN,
	/** 답변 LLM이 제공된 FAQ끼리 내용이 서로 달라 확정할 수 없다고 판단했습니다(칸 status CONFLICT). */
	LLM_CONFLICT
}
