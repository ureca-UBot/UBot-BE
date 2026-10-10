package com.ubot.monitoring.dto.model.improvelayer;

import java.util.List;

public record FaqImprovementItem(
		Long faqId,
		String question,
		List<FaqImprovementType> improvementTypes,
		long topKCount,
		double averageSimilarity,
		// 평균 Top1 - Top2 Gap
		double averageTopGap,
		// Gap < 0.05인 비율
		double lowGapRate
) {
}
