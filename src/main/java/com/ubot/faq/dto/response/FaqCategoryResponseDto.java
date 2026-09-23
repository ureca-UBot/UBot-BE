package com.ubot.faq.dto.response;

import com.ubot.faq.entity.FaqCategory;

import java.time.LocalDateTime;

public record FaqCategoryResponseDto(
		Long faqCategoryId,
		String name,
		LocalDateTime createdAt,
		LocalDateTime updatedAt
) {
	public static FaqCategoryResponseDto from(FaqCategory faqCategory) {
		return new FaqCategoryResponseDto(
				faqCategory.getId(),
				faqCategory.getName(),
				faqCategory.getCreatedAt(),
				faqCategory.getUpdatedAt()
		);
	}
}