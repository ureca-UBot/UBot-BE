package com.ubot.faq.dto;

public record FakeFaqSearchResponseDto(Long faqId, String question, String answer, double similarityScore) {
}
