package com.ubot.forbiddenword.dto.request;

import com.ubot.forbiddenword.enums.ForbiddenWordStatus;
import jakarta.validation.constraints.Size;

public record ForbiddenWordUpdateRequestDto(
		@Size(max = 100) String word,
		ForbiddenWordStatus status
) {
}
