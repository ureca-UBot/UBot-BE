package com.ubot.monitoring.faqmonitoring.dto.response;

import com.ubot.monitoring.faqmonitoring.dto.model.improvelayer.FaqImprovementType;
import java.time.LocalDateTime;

/** 해제된 FAQ 개선 제외 이력. */
public record FaqImprovementExclusionHistoryResponse(
		Long exclusionId,
		Long faqId,
		String faqQuestion,
		FaqImprovementType improvementType,
		String memo,
		Long excludedBy,
		LocalDateTime excludedAt,
		Long releasedBy,
		LocalDateTime releasedAt,
		boolean outdated
) {
}
