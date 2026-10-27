package com.ubot.monitoring.faqmonitoring.dto.response;

import com.ubot.common.PageResponseDto;
import com.ubot.monitoring.faqmonitoring.dto.model.improvelayer.FaqImprovementItem;
import java.time.LocalDateTime;
import java.util.List;

public record FaqImprovementResponse(
		LocalDateTime evaluatedAt,
		List<FaqImprovementItem> items,
		int page,
		int size,
		long totalElements,
		int totalPages,
		boolean first,
		boolean last
) {
	public static FaqImprovementResponse of(LocalDateTime evaluatedAt, PageResponseDto<FaqImprovementItem> result) {
		return new FaqImprovementResponse(
				evaluatedAt, result.content(), result.page(), result.size(), result.totalElements(),
				result.totalPages(), result.first(), result.last()
		);
	}
}
