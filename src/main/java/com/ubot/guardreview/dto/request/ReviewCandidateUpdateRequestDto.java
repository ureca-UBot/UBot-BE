package com.ubot.guardreview.dto.request;

import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record ReviewCandidateUpdateRequestDto(@Positive long revision, @NotBlank @Size(max = 100) String expression,
		@NotBlank @Size(max = 2000) String reason) {
}
