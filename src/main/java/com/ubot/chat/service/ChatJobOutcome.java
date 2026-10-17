package com.ubot.chat.service;

import com.ubot.ai.dto.SubQuestionFaqs;
import com.ubot.chat.dto.response.ChatStoreDto;
import com.ubot.common.ErrorCode;
import java.util.List;

// Worker 계산 결과(저장 전). 성공이면 answer·subQuestions·store, 실패면 errorCode만 있음
// subQuestions는 하위 질문별 근거 FAQ이고 근거를 못 찾은 하위 질문도 빈 목록으로 들어 있음
public record ChatJobOutcome(String answer, List<SubQuestionFaqs> subQuestions, ChatStoreDto store, ErrorCode errorCode) {

	static ChatJobOutcome answered(String answer, List<SubQuestionFaqs> subQuestions, ChatStoreDto store) {
		return new ChatJobOutcome(answer, subQuestions, store, null);
	}

	public static ChatJobOutcome failed(ErrorCode errorCode) {
		return new ChatJobOutcome(null, List.of(), null, errorCode);
	}

	public boolean isFailed() {
		return errorCode != null;
	}
}
