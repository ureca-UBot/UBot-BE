package com.ubot.unanswered.dto.request;

import com.ubot.unanswered.enums.UnansweredGroupStatus;
import jakarta.validation.constraints.NotNull;

public record UnansweredGroupStatusUpdateRequestDto(
		@NotNull UnansweredGroupStatus status
) {
}
