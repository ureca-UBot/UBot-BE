package com.ubot.monitoring.dto.model.improvelayer;

import com.ubot.faq.enums.Intent;
import java.time.LocalDateTime;

public record FaqImprovementMetric(
		Long faqId,
		String question,
		Intent intent,
		int faqVersion,
		LocalDateTime versionStartedAt,
		long topKCount,
		double averageSimilarity,
		long top1SampleCount,
		long recentUsageCount,
		double averageTopGap,
		double lowGapRate
) {
}
