package com.ubot.ai.dto;

import com.fasterxml.jackson.annotation.JsonIgnore;

/** LLM 답변과, 도구가 조회한 매장 지도 정보입니다. storeMap은 매장을 조회하지 않았으면 null입니다. */
public record AiAnswer(String answer, StoreMapResult storeMap, boolean locationRequired, String status,
		@JsonIgnore OutputGuardResult outputGuardResult) {
	public AiAnswer(String answer, StoreMapResult storeMap, boolean locationRequired, String status) {
		this(answer, storeMap, locationRequired, status, null);
	}

	public AiAnswer(String answer, StoreMapResult storeMap, boolean locationRequired) {
		this(answer, storeMap, locationRequired, null);
	}

	public boolean abstained() {
		return "ABSTAIN".equals(status);
	}
}
