package com.ubot.faq.dto.request;

import com.ubot.faq.enums.Intent;
import jakarta.validation.constraints.*;

public record FaqUpdateRequestDto(
		@Positive @NotNull Long id,
		@Positive @NotNull Long categoryId,
		@NotBlank @Size(max = 1000)String question,
		@NotBlank @Size(max = 1000)String answer,
		@NotNull Intent intent,
		Boolean cacheable
){
	public FaqUpdateRequestDto(Long id, Long categoryId, String question, String answer, Intent intent) {
		this(id, categoryId, question, answer, intent, null);
	}
}
