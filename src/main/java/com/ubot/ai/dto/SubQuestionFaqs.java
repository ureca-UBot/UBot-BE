package com.ubot.ai.dto;

import com.ubot.faq.dto.response.FaqSearchResponseDto;
import java.util.List;

/**
 * 하위 질문 하나와 그 질문으로 찾은 FAQ입니다. 유사도 기준을 넘은 FAQ가 없으면 faqs는 비어 있습니다.
 * 질문을 나누지 않았으면 subQuestion은 사용자가 입력한 원문과 같습니다.
 */
public record SubQuestionFaqs(String subQuestion, List<FaqSearchResponseDto> faqs) {

    public SubQuestionFaqs {
        faqs = faqs == null ? List.of() : List.copyOf(faqs);
    }

    public boolean isAnswered() {
        return !faqs.isEmpty();
    }

    /** 하위 질문이 원문과 다르게 다시 쓰이거나 여러 개로 나뉘었는지 반환합니다. */
    public static boolean isRewritten(String question, List<SubQuestionFaqs> subQuestions) {
        return subQuestions != null && (subQuestions.size() > 1
                || (subQuestions.size() == 1 && !subQuestions.getFirst().subQuestion().equals(question)));
    }
}
