package com.ubot.monitoring.faqmonitoring.dto.request;

import com.ubot.monitoring.faqmonitoring.dto.model.improvelayer.FaqImprovementType;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import java.util.List;

public record AiReviewRequest(
		@NotEmpty List<@NotNull FaqImprovementType> improvementTypes,
		@Valid DuplicateSuspectedReviewRequest duplicateSuspected
) {
}
