package com.ubot.monitoring.faqmonitoring.dto.response;

import com.ubot.monitoring.faqmonitoring.dto.model.improvelayer.FaqImprovementType;
import java.time.LocalDateTime;

public record FaqImprovementExclusionResponse(
		Long exclusionId,
		Long faqId,
		String faqQuestion,
		FaqImprovementType improvementType,
		String memo,
		Long excludedBy,
		LocalDateTime excludedAt,
		boolean outdated
) {
}
