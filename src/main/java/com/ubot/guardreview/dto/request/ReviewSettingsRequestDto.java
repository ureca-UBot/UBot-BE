package com.ubot.guardreview.dto.request;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.DecimalMax;

public record ReviewSettingsRequestDto(boolean enabled, @Min(5) @Max(43200) int intervalMinutes,
		@DecimalMin("0.5") @DecimalMax("1.0") double groupingThreshold, @Positive long revision) {
}
