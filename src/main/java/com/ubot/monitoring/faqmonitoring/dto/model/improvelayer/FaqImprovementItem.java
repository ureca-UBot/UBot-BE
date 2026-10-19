package com.ubot.monitoring.faqmonitoring.dto.model.improvelayer;

import com.ubot.faq.enums.Intent;
import java.time.LocalDateTime;
import java.util.List;

public record FaqImprovementItem(
		Long faqId,
		String question,
		Intent intent,
		int faqVersion,
		LocalDateTime versionStartedAt,
		List<FaqImprovementType> improvementTypes,
		long topKCount,
		double averageSimilarity,
		long top1SampleCount,
		long recentUsageCount,
		double averageTopGap,
		double lowGapRate
) {
}
