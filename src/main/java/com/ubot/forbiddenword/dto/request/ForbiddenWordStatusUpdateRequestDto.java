package com.ubot.forbiddenword.dto.request;

import com.ubot.forbiddenword.enums.ForbiddenWordStatus;

public record ForbiddenWordStatusUpdateRequestDto(
		ForbiddenWordStatus status
) {
}
