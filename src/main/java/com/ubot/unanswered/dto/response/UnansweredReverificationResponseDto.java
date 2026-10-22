package com.ubot.unanswered.dto.response;

public record UnansweredReverificationResponseDto(
		Long questionId,
		Long faqId,
		String faqQuestion,
		Double beforeSimilarity,
		Double afterSimilarity
) {
}
