package com.ubot.monitoring.faqmonitoring.dto.model.improvelayer;

public record FaqDuplicateCandidate(
		Long faqId,
		String question,
		long top1Count,
		long pairCount
) {
}
