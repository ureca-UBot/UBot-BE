package com.ubot.guardreview.dto.request;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.util.List;

public record ReviewCheckAnalysisRequestDto(@NotEmpty List<@NotNull @Positive Long> checkIds) {
}
