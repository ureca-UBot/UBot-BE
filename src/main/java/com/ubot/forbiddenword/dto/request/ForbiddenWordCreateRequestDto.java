package com.ubot.forbiddenword.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record ForbiddenWordCreateRequestDto(
		@NotBlank @Size(max = 100) String word
) {
}
