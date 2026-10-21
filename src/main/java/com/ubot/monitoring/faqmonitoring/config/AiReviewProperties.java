package com.ubot.monitoring.faqmonitoring.config;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import lombok.Getter;
import lombok.Setter;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.convert.DurationMin;
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

	@NotNull
	@DurationMin(millis = 1)
	private Duration maxLlmQueueWait = Duration.ofSeconds(5);

	@Override
	public void afterPropertiesSet() {
		if (maxDisplayedQuestionsPerDuplicateCandidate > maxQuestionsPerDuplicateCandidate) {
			maxDisplayedQuestionsPerDuplicateCandidate = maxQuestionsPerDuplicateCandidate;
		}
	}
}
