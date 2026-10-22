package com.ubot.monitoring.faqmonitoring.dto.model.improvelayer;

import java.util.List;

public record FaqDuplicateEvidence(
		Long candidateFaqId,
		long pairCount,
		List<String> questions
) {
}
