package com.ubot.ai.dto;

/** LLM 답변과, 도구가 조회한 매장 지도 정보입니다. storeMap은 매장을 조회하지 않았으면 null입니다. */
public record AiAnswer(String answer, StoreMapResult storeMap, boolean locationRequired, String status) {
	public AiAnswer(String answer, StoreMapResult storeMap, boolean locationRequired) {
		this(answer, storeMap, locationRequired, null);
	}

	public boolean abstained() {
		return "ABSTAIN".equals(status);
	}
}
