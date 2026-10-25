package com.ubot.guardreview.dto.request;

import com.ubot.guardreview.model.ReviewCheckModels.Origin;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.util.List;

public record ReviewReassessmentRequestDto(@NotNull Origin origin,
		@NotEmpty @Size(max = 50) List<@NotNull @Positive Long> checkIds) {
}
