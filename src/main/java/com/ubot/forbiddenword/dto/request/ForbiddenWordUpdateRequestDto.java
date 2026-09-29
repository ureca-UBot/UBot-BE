package com.ubot.forbiddenword.dto.request;

import jakarta.validation.constraints.Size;

public record ForbiddenWordUpdateRequestDto(
		@Size(max = 100) String word
) {
}
