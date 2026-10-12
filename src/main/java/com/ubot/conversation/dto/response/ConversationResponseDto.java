package com.ubot.conversation.dto.response;

import com.ubot.conversation.repository.ConversationRepository.ConversationSummary;
import java.time.LocalDateTime;
import java.util.UUID;

public record ConversationResponseDto(
		UUID conversationId,
		String title,
		LocalDateTime lastQuestionAt
) {
	public static ConversationResponseDto from(ConversationSummary summary){
		return new ConversationResponseDto(summary.getPublicId(), summary.getTitle(), summary.getLastQuestionAt());
	}
}
