package com.ubot.faq.dto.response;

import com.ubot.faq.entity.FaqLog;

import java.time.LocalDateTime;

public record FaqLogResponseDto(
		Long id,
		Long questionLogId,
		Long faqId,
		Integer rank,
		Double similarity,
		LocalDateTime createdAt

) {
	public static FaqLogResponseDto from(FaqLog faqLog) {
		return new FaqLogResponseDto(
				faqLog.getId(),
				faqLog.getQuestionLogId(),
				faqLog.getFaq().getId(),
				faqLog.getRank(),
				faqLog.getSimilarity(),
				faqLog.getCreatedAt()
		);
	}
}