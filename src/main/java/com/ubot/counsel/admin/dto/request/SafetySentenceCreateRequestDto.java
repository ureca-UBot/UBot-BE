package com.ubot.counsel.admin.dto.request;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record SafetySentenceCreateRequestDto(
		@NotBlank @Size(max = 1000) String content,
		@NotNull @Min(1) @Max(3) Integer severity) {}
