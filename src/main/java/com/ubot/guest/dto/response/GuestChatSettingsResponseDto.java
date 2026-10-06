package com.ubot.guest.dto.response;

import com.ubot.guest.entity.GuestChatSettings;
import java.time.LocalDateTime;

public record GuestChatSettingsResponseDto(
		int maxQuestionCount,
		Long updatedBy,
		LocalDateTime updatedAt
) {
	public static GuestChatSettingsResponseDto from(GuestChatSettings settings) {
		return new GuestChatSettingsResponseDto(
				settings.getMaxQuestionCount(),
				settings.getUpdatedBy(),
				settings.getUpdatedAt()
		);
	}
}
