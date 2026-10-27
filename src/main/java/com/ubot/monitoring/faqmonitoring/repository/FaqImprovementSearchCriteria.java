package com.ubot.monitoring.faqmonitoring.repository;

import com.ubot.monitoring.faqmonitoring.dto.model.improvelayer.FaqImprovementType;
import java.time.LocalDateTime;
import java.util.List;

public record FaqImprovementSearchCriteria(
		LocalDateTime evaluatedAt,
		LocalDateTime unusedCutoffAt,
		int topK,
		double lowGapThreshold,
		double lowSimilarityThreshold,
		double lowGapRateThreshold,
		int minimumTopKSampleCount,
		int minimumTop1SampleCount,
		List<FaqImprovementType> types,
		int page,
		int size
) {
}
