package com.ubot.faq.dto.request;

import jakarta.validation.constraints.*;

public record FaqUpdateRequestDto(
		@Positive @NotNull Long id,
		@NotBlank @Size(max = 100)String category,
		@NotBlank @Size(max = 1000)String question,
		@NotBlank @Size(max = 1000)String answer
){
}