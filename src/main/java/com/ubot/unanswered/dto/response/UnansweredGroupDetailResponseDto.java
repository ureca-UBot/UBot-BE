package com.ubot.unanswered.dto.response;

import com.ubot.unanswered.entity.UnansweredQuestion;
import com.ubot.unanswered.entity.UnansweredQuestionGroup;
import com.ubot.unanswered.enums.UnansweredGroupStatus;

import java.time.LocalDateTime;
import java.util.List;

public record UnansweredGroupDetailResponseDto(
		Long id,
		String representativeQuestion,
		Integer questionCount,
		Long relatedFaqId,
		UnansweredGroupStatus status,
		LocalDateTime lastOccurredAt,
		LocalDateTime createdAt,
		List<UnansweredQuestionResponseDto> questions
) {
	public static UnansweredGroupDetailResponseDto from(UnansweredQuestionGroup group, List<UnansweredQuestion> questions){
		return new UnansweredGroupDetailResponseDto(
				group.getId(),
				group.getRepresentativeQuestion(),
				group.getQuestionCount(),
				group.getRelatedFaqId(),
				group.getStatus(),
				group.getLastOccurredAt(),
				group.getCreatedAt(),
				questions.stream().map(UnansweredQuestionResponseDto::from).toList()
		);
	}
}
