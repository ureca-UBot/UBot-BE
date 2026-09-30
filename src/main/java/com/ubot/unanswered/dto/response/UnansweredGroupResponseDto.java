package com.ubot.unanswered.dto.response;

import com.ubot.unanswered.entity.UnansweredQuestionGroup;
import com.ubot.unanswered.enums.UnansweredGroupStatus;

import java.time.LocalDateTime;

public record UnansweredGroupResponseDto(
		Long id,
		String representativeQuestion,
		Integer questionCount,
		Long relatedFaqId,
		UnansweredGroupStatus status,
		LocalDateTime lastOccurredAt
) {
	public static UnansweredGroupResponseDto from(UnansweredQuestionGroup group){
		return new UnansweredGroupResponseDto(
				group.getId(),
				group.getRepresentativeQuestion(),
				group.getQuestionCount(),
				group.getRelatedFaqId(),
				group.getStatus(),
				group.getLastOccurredAt()
		);
	}
}
