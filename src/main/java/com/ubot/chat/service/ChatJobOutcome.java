package com.ubot.chat.service;

import com.ubot.chat.dto.response.ChatStoreDto;
import com.ubot.common.ErrorCode;
import com.ubot.faq.dto.response.FaqSearchResponseDto;
import java.util.List;

// Worker 계산 결과(저장 전). 성공이면 answer·faqs·store, 실패면 errorCode만 있음
public record ChatJobOutcome(String answer, List<FaqSearchResponseDto> faqs, ChatStoreDto store, ErrorCode errorCode) {

	static ChatJobOutcome answered(String answer, List<FaqSearchResponseDto> faqs, ChatStoreDto store) {
		return new ChatJobOutcome(answer, faqs, store, null);
	}

	public static ChatJobOutcome failed(ErrorCode errorCode) {
		return new ChatJobOutcome(null, List.of(), null, errorCode);
	}

	public boolean isFailed() {
		return errorCode != null;
	}
}
