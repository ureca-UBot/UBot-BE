package com.ubot.faq.dto;

public record FaqSearchResponseDto(Long faqId, String question, String answer, double similarityScore) {
}
