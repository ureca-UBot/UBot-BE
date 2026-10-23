package com.ubot.chat.service;

import com.ubot.ai.dto.AiAnswer;
import com.ubot.ai.dto.SubQuestionFaqs;
import com.ubot.chat.dto.SurgingQuestionEmbedding;
import com.ubot.common.ErrorCode;
import com.ubot.faq.dto.response.FaqSearchResponseDto;
import java.util.List;
import java.util.Map;

/**
 * 답변 계산 결과입니다. 답변을 만들었으면 answer와 하위 질문별 근거 FAQ가, 만들지 못했으면 errorCode가 있습니다.
 * subQuestions에는 근거를 찾지 못한 하위 질문도 빈 FAQ 목록으로 들어 있습니다. 목록 순서가 하위 질문 순번입니다.
 */
record ChatAnswerResult(AiAnswer answer, List<SubQuestionFaqs> subQuestions,
		Map<Integer, SurgingQuestionEmbedding> questionEmbeddings, ErrorCode errorCode) {

	ChatAnswerResult {
		questionEmbeddings = questionEmbeddings == null ? Map.of() : Map.copyOf(questionEmbeddings);
	}

	static ChatAnswerResult answered(AiAnswer answer, List<SubQuestionFaqs> subQuestions) {
		return answered(answer, subQuestions, Map.of());
	}

	static ChatAnswerResult answered(AiAnswer answer, List<SubQuestionFaqs> subQuestions,
			Map<Integer, SurgingQuestionEmbedding> questionEmbeddings) {
		return new ChatAnswerResult(answer, List.copyOf(subQuestions), questionEmbeddings, null);
	}

	static ChatAnswerResult failed(ErrorCode errorCode) {
		return new ChatAnswerResult(null, null, Map.of(), errorCode);
	}

	boolean isFailed() {
		return errorCode != null;
	}

	/** 모든 하위 질문의 근거 FAQ를 하위 질문 순서대로 이어 붙입니다. 같은 FAQ가 여러 하위 질문에 있으면 여러 번 나옵니다. */
	List<FaqSearchResponseDto> faqs() {
		return subQuestions == null ? null
				: subQuestions.stream().flatMap(subQuestion -> subQuestion.faqs().stream()).toList();
	}
}
