package com.ubot.faq.dto.response;

import com.ubot.faq.entity.Faq;
import com.ubot.faq.entity.OldFaq;

import java.time.LocalDateTime;

public record OldFaqResponseDto (
		Long faqId,
		Integer version,
		Long categoryId,
		String question,
		String answer,
		Long createdById,
		Long updatedById,
		LocalDateTime updatedAt
){
	public static OldFaqResponseDto from(OldFaq oldFaq) {
		return new OldFaqResponseDto(
				oldFaq.getFaqId(),
				oldFaq.getVersion(),
				oldFaq.getFaqCategory().getId(),
				oldFaq.getQuestion(),
				oldFaq.getAnswer(),
				oldFaq.getCreatedBy().getId(),
				oldFaq.getUpdatedBy().getId(),
				oldFaq.getUpdatedAt()
		);
	}
}