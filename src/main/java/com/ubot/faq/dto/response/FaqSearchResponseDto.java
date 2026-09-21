package com.ubot.faq.dto.response;

public record FaqSearchResponseDto(Long faqId, String question, String answer, double similarityScore) {
}