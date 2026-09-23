package com.ubot.faq.dto.response;

import com.ubot.faq.entity.Faq;

import java.time.LocalDateTime;

public record FaqResponseDto(
		Long id,
		Long categoryId,
		String question,
		String answer,
		Integer version,
		Long adminId,
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
				faq.getCreatedAt(),
				faq.getUpdatedAt()
		);
	}
}