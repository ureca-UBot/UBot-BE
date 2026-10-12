package com.ubot.conversation.dto.response;

import com.ubot.chat.entity.QuestionLog;
import java.time.LocalDateTime;

public record ConversationMessageResponseDto(
		Long id,
		String question,
		String answer,
		LocalDateTime createdAt
) {
	public static ConversationMessageResponseDto from(QuestionLog questionLog){
		return new ConversationMessageResponseDto(
				questionLog.getId(),
				questionLog.getUserQuestion(),
				questionLog.getAnswer(),
				questionLog.getCreatedAt()
		);
	}
}
