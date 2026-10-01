package com.ubot.unanswered.dto.request;

import com.ubot.faq.enums.Intent;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

public record UnansweredGroupFaqCreateRequestDto(
		@NotNull @Positive Long categoryId,
		@Size(max = 1000) String question,
		@NotBlank @Size(max = 1000) String answer,
		@NotNull Intent intent
) {
}
