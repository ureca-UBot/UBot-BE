package com.ubot.unanswered.dto.response;

import com.ubot.unanswered.entity.UnansweredQuestionGroup;
import com.ubot.unanswered.enums.UnansweredGroupStatus;
import com.ubot.unanswered.repository.UnansweredReverifyRepository.ReverificationSummary;

import java.time.LocalDateTime;

public record UnansweredGroupResponseDto(
		Long id,
		String representativeQuestion,
		String topicName,
		Long suggestedCategoryId,
		Integer questionCount,
		Long relatedFaqId,
		Long resolvedFaqId,
		UnansweredGroupStatus status,
		LocalDateTime lastOccurredAt,
		Long reverifiedFaqId,
		Integer resolvableQuestionCount
) {
	public static UnansweredGroupResponseDto from(UnansweredQuestionGroup group){
		return from(group, null);
	}

	public static UnansweredGroupResponseDto from(UnansweredQuestionGroup group, ReverificationSummary reverification){
		return new UnansweredGroupResponseDto(
				group.getId(),
				group.getRepresentativeQuestion(),
				group.getTopicName(),
				group.getSuggestedCategoryId(),
				group.getQuestionCount(),
				group.getRelatedFaqId(),
				group.getResolvedFaqId(),
				group.getStatus(),
				group.getLastOccurredAt(),
				reverification == null ? null : reverification.faqId(),
				reverification == null ? 0 : reverification.questionCount()
		);
	}
}
