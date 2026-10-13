package com.ubot.faq.dto.response;

/** faq에는 공식 질문·답변을 유지하고, 비교에 사용한 표현은 별도로 보관합니다. */
public record SemanticFaqCandidateResponseDto(
        FaqSearchResponseDto faq,
        String matchedQuestion
) {
}
