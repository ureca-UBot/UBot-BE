package com.ubot.ai.dto;

import com.ubot.faq.dto.response.FaqSearchResponseDto;
import java.util.List;

/**
 * 하위 질문 하나와 그 질문으로 찾은 FAQ입니다. 유사도 기준을 넘은 FAQ가 없으면 faqs는 비어 있습니다.
 * searchQuery는 검색에 쓴 검색어(판정 LLM이 다시 쓴 것)이고, userText는 그 하위 질문에 해당하는 사용자 원문 조각입니다.
 * 답변 LLM에는 검색어를 보여 주지 않고 userText만 보여 줍니다(검색어에는 판정 LLM의 의미 변형이 섞일 수 있습니다).
 * 질문을 나누지 않았으면 userText는 사용자가 입력한 원문입니다. 질문을 나눴지만 원문을 자를 근거가 없었으면 null이며,
 * 이때 답변 LLM에는 하위 질문별 제목 없이 원문만 보여 줍니다.
 */
public record SubQuestionFaqs(String searchQuery, List<FaqSearchResponseDto> faqs, String userText) {

    public SubQuestionFaqs {
        faqs = faqs == null ? List.of() : List.copyOf(faqs);
    }

    public boolean isAnswered() {
        return !faqs.isEmpty();
    }
}
