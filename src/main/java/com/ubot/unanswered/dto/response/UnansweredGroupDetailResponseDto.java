package com.ubot.unanswered.dto.response;

import com.ubot.unanswered.entity.UnansweredQuestion;
import com.ubot.unanswered.entity.UnansweredQuestionGroup;
import com.ubot.unanswered.enums.UnansweredGroupStatus;

import java.time.LocalDateTime;
import java.util.List;

public record UnansweredGroupDetailResponseDto(
		Long id,
		String representativeQuestion,
		String topicName,
		Long suggestedCategoryId,
		Integer questionCount,
		Long relatedFaqId,
		Long resolvedFaqId,
		UnansweredGroupStatus status,
		LocalDateTime lastOccurredAt,
		LocalDateTime createdAt,
		List<UnansweredQuestionResponseDto> questions,
		List<UnansweredReverificationResponseDto> reverifications
) {
	public static UnansweredGroupDetailResponseDto from(UnansweredQuestionGroup group, List<UnansweredQuestion> questions,
			List<UnansweredReverificationResponseDto> reverifications){
		return new UnansweredGroupDetailResponseDto(
				group.getId(),
				group.getRepresentativeQuestion(),
				group.getTopicName(),
				group.getSuggestedCategoryId(),
				group.getQuestionCount(),
				group.getRelatedFaqId(),
				group.getResolvedFaqId(),
				group.getStatus(),
				group.getLastOccurredAt(),
				group.getCreatedAt(),
				questions.stream().map(UnansweredQuestionResponseDto::from).toList(),
				reverifications
		);
	}
}
