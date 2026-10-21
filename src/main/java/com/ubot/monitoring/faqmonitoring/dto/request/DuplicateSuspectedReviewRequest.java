package com.ubot.monitoring.faqmonitoring.dto.request;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.util.List;

public record DuplicateSuspectedReviewRequest(
		@NotNull @Size(min = 1, max = 5) List<@NotNull @Positive Long> candidateFaqIds
) {
}
