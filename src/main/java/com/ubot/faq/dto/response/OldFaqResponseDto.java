package com.ubot.faq.dto.response;

import com.ubot.faq.entity.Faq;
import com.ubot.faq.entity.OldFaq;
import com.ubot.faq.enums.Intent;

import java.time.LocalDateTime;

public record OldFaqResponseDto (
		Long faqId,
		Integer version,
		Long categoryId,
		String question,
		String answer,
		Intent intent,
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
				oldFaq.getIntent(),
				oldFaq.getCreatedBy().getId(),
				oldFaq.getUpdatedBy().getId(),
				oldFaq.getUpdatedAt()
		);
	}
}