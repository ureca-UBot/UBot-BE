package com.ubot.faq.dto.response;

import com.ubot.faq.entity.Faq;
import com.ubot.faq.enums.Intent;

import java.time.LocalDateTime;
import java.util.List;

public record FaqResponseDto(
		Long id,
		Long categoryId,
		String question,
		String answer,
		Integer version,
		Long adminId,
		Intent intent,
		boolean cacheable,
		List<String> similarQuestions,
		LocalDateTime createdAt,
		LocalDateTime updatedAt

) {
	public static FaqResponseDto from(Faq faq) {
		return from(faq, List.of());
	}

	public static FaqResponseDto from(Faq faq, List<String> similarQuestions) {
		return new FaqResponseDto(
				faq.getId(),
				faq.getFaqCategory().getId(),
				faq.getQuestion(),
				faq.getAnswer(),
				faq.getVersion(),
				// 등록한 관리자가 삭제되면 FK가 SET NULL로 비워집니다.
				faq.getAdmin() == null ? null : faq.getAdmin().getId(),
				faq.getIntent(),
				faq.isCacheable(),
				List.copyOf(similarQuestions),
				faq.getCreatedAt(),
				faq.getUpdatedAt()
		);
	}
}
