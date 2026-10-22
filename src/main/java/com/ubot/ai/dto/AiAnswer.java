package com.ubot.ai.dto;

import com.fasterxml.jackson.annotation.JsonIgnore;

/**
 * LLM 답변과, 화면에 따로 보여 줄 조회 결과입니다. storeMap은 매장을 조회하지 않았으면 null입니다.
 * status는 모델이 낸 답변 상태(ANSWER·ABSTAIN 등)이고, outputGuardResult는 출력 검사의 내부 결과입니다.
 * userData는 답변에 쓰인 요금제·사용량·청구서 카드, productData는 답변에 쓰인 상품 카드이며 없으면 null입니다.
 */
public record AiAnswer(String answer, StoreMapResult storeMap, boolean locationRequired, String status,
		@JsonIgnore OutputGuardResult outputGuardResult, UserDataCards userData, ProductDataCards productData) {

	public AiAnswer(String answer, StoreMapResult storeMap, boolean locationRequired) {
		this(answer, storeMap, locationRequired, null, null, null, null);
	}

	public AiAnswer(String answer, StoreMapResult storeMap, boolean locationRequired, String status) {
		this(answer, storeMap, locationRequired, status, null, null, null);
	}

	public AiAnswer(String answer, StoreMapResult storeMap, boolean locationRequired, String status,
			OutputGuardResult outputGuardResult) {
		this(answer, storeMap, locationRequired, status, outputGuardResult, null, null);
	}

	public AiAnswer(String answer, StoreMapResult storeMap, boolean locationRequired, String status,
			OutputGuardResult outputGuardResult, UserDataCards userData) {
		this(answer, storeMap, locationRequired, status, outputGuardResult, userData, null);
	}

	public AiAnswer(String answer, StoreMapResult storeMap, boolean locationRequired, String status,
			UserDataCards userData) {
		this(answer, storeMap, locationRequired, status, null, userData, null);
	}

	public boolean abstained() {
		return "ABSTAIN".equals(status);
	}
}
