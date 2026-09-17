package com.ubot.faq.dto.response;

import com.ubot.faq.entity.Faq;
import com.ubot.faq.repository.FaqRepository;

public record FaqCreateResponseDto(
		Long id,
		String question,
		String answer
) {
	public static FaqCreateResponseDto from(Faq faq) {
		return new FaqCreateResponseDto(
				faq.getId(),
				faq.getQuestion(),
				faq.getAnswer()
		);
	}
}
