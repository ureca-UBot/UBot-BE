package com.ubot.faq.dto.response;

import com.ubot.faq.entity.Faq;
import com.ubot.faq.enums.Intent;

import java.time.LocalDateTime;

public record FaqResponseDto(
		Long id,
		Long categoryId,
		String question,
		String answer,
		Integer version,
		Long adminId,
		Intent intent,
		LocalDateTime createdAt,
		LocalDateTime updatedAt

) {
	public static FaqResponseDto from(Faq faq) {
		return new FaqResponseDto(
				faq.getId(),
				faq.getFaqCategory().getId(),
				faq.getQuestion(),
				faq.getAnswer(),
				faq.getVersion(),
				faq.getAdmin().getId(),
				faq.getIntent(),
				faq.getCreatedAt(),
				faq.getUpdatedAt()
		);
	}
}