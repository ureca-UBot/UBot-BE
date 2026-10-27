package com.ubot.faq.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record FaqCategoryUpdateRequestDto (
		@NotBlank @Size(max = 100) String afterName
){
}