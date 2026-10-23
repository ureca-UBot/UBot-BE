package com.ubot.chat.service;

import com.ubot.ai.dto.SubQuestionFaqs;
import com.ubot.ai.dto.UserDataCards;
import com.ubot.chat.dto.response.ChatStoreDto;
import com.ubot.common.ErrorCode;
import com.ubot.unanswered.dto.UnansweredCandidate;
import java.util.List;

// Worker 계산 결과(저장 전). 성공이면 answer·subQuestions·store·userData, 실패면 errorCode만 있음
// subQuestions는 하위 질문별 근거 FAQ이고 근거를 못 찾은 하위 질문도 빈 목록으로 들어 있음
// userData는 가입 정보 답변에 쓰인 요금제·사용량·청구서 카드이고, 없으면 null
public record ChatJobOutcome(String answer, List<SubQuestionFaqs> subQuestions, ChatStoreDto store,
		UserDataCards userData, ErrorCode errorCode, List<UnansweredCandidate> unanswered) {

	public ChatJobOutcome {
		unanswered = unanswered == null ? List.of() : List.copyOf(unanswered);
	}

	public ChatJobOutcome(String answer, List<SubQuestionFaqs> subQuestions, ChatStoreDto store, ErrorCode errorCode,
			List<UnansweredCandidate> unanswered) {
		this(answer, subQuestions, store, null, errorCode, unanswered);
	}

	static ChatJobOutcome answered(String answer, List<SubQuestionFaqs> subQuestions, ChatStoreDto store,
			List<UnansweredCandidate> unanswered) {
		return answered(answer, subQuestions, store, null, unanswered);
	}

	static ChatJobOutcome answered(String answer, List<SubQuestionFaqs> subQuestions, ChatStoreDto store,
			UserDataCards userData, List<UnansweredCandidate> unanswered) {
		return new ChatJobOutcome(answer, subQuestions, store, userData, null, unanswered);
	}

	public static ChatJobOutcome failed(ErrorCode errorCode) {
		return failed(errorCode, List.of());
	}

	public static ChatJobOutcome failed(ErrorCode errorCode, List<UnansweredCandidate> unanswered) {
		return new ChatJobOutcome(null, List.of(), null, null, errorCode, unanswered);
	}

	public boolean isFailed() {
		return errorCode != null;
	}
}
