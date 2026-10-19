package com.ubot.chat.service;

import com.ubot.ai.dto.UserDataCards;
import com.ubot.chat.dto.response.ChatStoreDto;
import com.ubot.common.ErrorCode;
import com.ubot.faq.dto.response.FaqSearchResponseDto;
import java.util.List;

// Worker 계산 결과(저장 전). 성공이면 answer·faqs·store·userData, 실패면 errorCode만 있음
// userData는 가입 정보 답변에 쓰인 요금제·사용량·청구서 카드이고, 없으면 null
public record ChatJobOutcome(String answer, List<FaqSearchResponseDto> faqs, ChatStoreDto store, UserDataCards userData,
		ErrorCode errorCode) {

	public ChatJobOutcome(String answer, List<FaqSearchResponseDto> faqs, ChatStoreDto store, ErrorCode errorCode) {
		this(answer, faqs, store, null, errorCode);
	}

	static ChatJobOutcome answered(String answer, List<FaqSearchResponseDto> faqs, ChatStoreDto store) {
		return answered(answer, faqs, store, null);
	}

	static ChatJobOutcome answered(
			String answer, List<FaqSearchResponseDto> faqs, ChatStoreDto store, UserDataCards userData) {
		return new ChatJobOutcome(answer, faqs, store, userData, null);
	}

	public static ChatJobOutcome failed(ErrorCode errorCode) {
		return new ChatJobOutcome(null, List.of(), null, null, errorCode);
	}

	public boolean isFailed() {
		return errorCode != null;
	}
}
