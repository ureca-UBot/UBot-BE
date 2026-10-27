package com.ubot.faq.dto.request;

import jakarta.validation.constraints.NotEmpty;

import java.util.List;

public record FaqRestoreRequestDto(
		@NotEmpty List<Long> faqIds
) {
}
