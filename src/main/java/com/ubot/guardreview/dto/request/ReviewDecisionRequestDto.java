package com.ubot.guardreview.dto.request;

import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

public record ReviewDecisionRequestDto(@Positive long revision,
		@NotBlank @Pattern(regexp = "DISABLE|REJECT|REVIEWED") String action) {
}
