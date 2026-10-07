package com.ubot.chat.service;

import com.ubot.ai.dto.AiAnswer;
import com.ubot.common.ErrorCode;
import com.ubot.faq.dto.response.FaqSearchResponseDto;
import java.util.List;

/** 답변 계산 결과입니다. 답변을 만들었으면 answer와 근거 FAQ가, 만들지 못했으면 errorCode가 있습니다. */
record ChatAnswerResult(AiAnswer answer, List<FaqSearchResponseDto> faqs, ErrorCode errorCode) {

	static ChatAnswerResult answered(AiAnswer answer, List<FaqSearchResponseDto> faqs) {
		return new ChatAnswerResult(answer, faqs, null);
	}

	static ChatAnswerResult failed(ErrorCode errorCode) {
		return new ChatAnswerResult(null, null, errorCode);
	}

	boolean isFailed() {
		return errorCode != null;
	}
}
