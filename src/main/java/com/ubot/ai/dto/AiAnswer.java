package com.ubot.ai.dto;

import com.fasterxml.jackson.annotation.JsonIgnore;
import java.util.List;

/**
 * LLM 답변과, 화면에 따로 보여 줄 조회 결과입니다. storeMap은 매장을 조회하지 않았으면 null입니다.
 * status는 답변 상태(ANSWER·ABSTAIN 등)이고, outputGuardResult는 출력 검사의 내부 결과입니다.
 * 칸 형식 답변이면 status는 하위 질문별 status를 조합한 값이고, slotOutcomes는 칸마다의 최종 status입니다(칸 형식이 아니면 null).
 * userData는 답변에 쓰인 요금제·사용량·청구서 카드이며 없으면 null입니다.
 */
public record AiAnswer(String answer, StoreMapResult storeMap, boolean locationRequired, String status,
		@JsonIgnore OutputGuardResult outputGuardResult, UserDataCards userData,
		@JsonIgnore List<SlotOutcome> slotOutcomes) {

	/**
	 * 칸 하나의 최종 status입니다. changedByGuard는 모델은 ANSWER로 냈지만 출력 가드가 내용을 지워 ABSTAIN으로 바꾼 경우입니다.
	 */
	public record SlotOutcome(String status, boolean changedByGuard) {
	}

	public AiAnswer(String answer, StoreMapResult storeMap, boolean locationRequired, String status,
			OutputGuardResult outputGuardResult, UserDataCards userData) {
		this(answer, storeMap, locationRequired, status, outputGuardResult, userData, null);
	}

	public AiAnswer(String answer, StoreMapResult storeMap, boolean locationRequired) {
		this(answer, storeMap, locationRequired, null, null, null);
	}

	public AiAnswer(String answer, StoreMapResult storeMap, boolean locationRequired, String status) {
		this(answer, storeMap, locationRequired, status, null, null);
	}

	public AiAnswer(String answer, StoreMapResult storeMap, boolean locationRequired, String status,
			OutputGuardResult outputGuardResult) {
		this(answer, storeMap, locationRequired, status, outputGuardResult, null);
	}

	public AiAnswer(String answer, StoreMapResult storeMap, boolean locationRequired, String status,
			UserDataCards userData) {
		this(answer, storeMap, locationRequired, status, null, userData);
	}

	public boolean abstained() {
		return "ABSTAIN".equals(status);
	}
}
