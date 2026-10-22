package com.ubot.monitoring.faqmonitoring.dto.request;

import com.ubot.monitoring.faqmonitoring.dto.model.improvelayer.FaqImprovementType;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record FaqImprovementExclusionRequest(
		@NotNull FaqImprovementType improvementType,
		@Size(max = 500) String memo
) {
}
