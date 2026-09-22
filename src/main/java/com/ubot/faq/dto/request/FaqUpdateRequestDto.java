package com.ubot.faq.dto.request;

import jakarta.validation.constraints.*;

public record FaqUpdateRequestDto(
		@Positive @NotNull Long id,
		@Positive @NotNull Long categoryId,
		@NotBlank @Size(max = 1000)String question,
		@NotBlank @Size(max = 1000)String answer
){
}