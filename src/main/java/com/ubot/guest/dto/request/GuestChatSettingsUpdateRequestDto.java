package com.ubot.guest.dto.request;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

public record GuestChatSettingsUpdateRequestDto(
		@NotNull @Min(1) Integer maxQuestionCount
) {
}
