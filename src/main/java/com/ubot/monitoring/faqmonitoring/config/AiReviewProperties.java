package com.ubot.monitoring.faqmonitoring.config;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Min;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties("faq-monitoring.ai-review")
public record AiReviewProperties(
		@Min(1) int maxDuplicateCandidates,
		@Min(1) int maxQuestionsPerDuplicateCandidate,
		@Min(1) int maxDisplayedQuestionsPerDuplicateCandidate,
		@Min(1) int maxLowRagQualityQuestions,
		@Min(1) int maxDisplayedQuestionsForLowRagQuality,
		@Min(1) long maxLlmQueueWaitMillis
) {
	@AssertTrue(message = "max-displayed-questions-per-duplicate-candidate must not exceed max-questions-per-duplicate-candidate")
	public boolean isDisplayedDuplicateQuestionsWithinLimit() {
		return maxDisplayedQuestionsPerDuplicateCandidate <= maxQuestionsPerDuplicateCandidate;
	}

	@AssertTrue(message = "max-displayed-questions-for-low-rag-quality must not exceed max-low-rag-quality-questions")
	public boolean isDisplayedLowRagQualityQuestionsWithinLimit() {
		return maxDisplayedQuestionsForLowRagQuality <= maxLowRagQualityQuestions;
	}
}
