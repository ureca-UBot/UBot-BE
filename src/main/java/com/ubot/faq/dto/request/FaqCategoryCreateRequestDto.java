package com.ubot.faq.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record FaqCategoryCreateRequestDto (
		@NotBlank @Size(max = 100) String name
){

}
