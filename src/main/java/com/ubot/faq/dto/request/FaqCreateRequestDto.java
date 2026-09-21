package com.ubot.faq.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record FaqCreateRequestDto(
		@NotBlank @Size(max = 100)String category,
		@NotBlank @Size(max = 1000)String question,
		@NotBlank @Size(max = 1000)String answer
){
}