package com.ubot.monitoring.faqmonitoring.config;

import jakarta.validation.constraints.Min;
import lombok.Getter;
import lombok.Setter;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Getter
@Setter
@Validated
@ConfigurationProperties(prefix = "faq-monitoring.ai-review")
public class AiReviewProperties implements InitializingBean {
	@Min(1)
	private int maxDuplicateCandidates = 5;

	@Min(1)
	private int maxQuestionsPerDuplicateCandidate = 20;

	@Min(1)
	private int maxDisplayedQuestionsPerDuplicateCandidate = 5;

	@Min(1)
	private int maxLowRagQualityQuestions = 20;

	@Min(1)
	private int maxDisplayedQuestionsForLowRagQuality = 5;

	@Min(1)
	private long maxLlmQueueWaitMillis = 5_000;

	@Override
	public void afterPropertiesSet() {
		if (maxDisplayedQuestionsPerDuplicateCandidate > maxQuestionsPerDuplicateCandidate) {
			maxDisplayedQuestionsPerDuplicateCandidate = maxQuestionsPerDuplicateCandidate;
		}
	}
}
