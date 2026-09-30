package com.ubot.unanswered.dto.response;

import com.ubot.unanswered.entity.UnansweredQuestion;
import com.ubot.unanswered.enums.UnansweredReason;

import java.time.LocalDateTime;

public record UnansweredQuestionResponseDto(
		Long id,
		String question,
		UnansweredReason reason,
		Long bestFaqId,
		Double bestSimilarity,
		LocalDateTime createdAt
) {
	public static UnansweredQuestionResponseDto from(UnansweredQuestion question){
		return new UnansweredQuestionResponseDto(
				question.getId(),
				question.getQuestion(),
				question.getReason(),
				question.getBestFaqId(),
				question.getBestSimilarity(),
				question.getCreatedAt()
		);
	}
}
