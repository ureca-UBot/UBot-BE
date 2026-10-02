package com.ubot.faq.dto.response;

import com.ubot.faq.enums.Intent;

public record FaqSearchResponseDto(Long faqId, String question, String answer, double similarityScore, Intent intent) {

    /** intent를 지정하지 않으면 기존 동작과 같은 GENERAL로 봅니다. */
    public FaqSearchResponseDto(Long faqId, String question, String answer, double similarityScore) {
        this(faqId, question, answer, similarityScore, Intent.GENERAL);
    }
}
