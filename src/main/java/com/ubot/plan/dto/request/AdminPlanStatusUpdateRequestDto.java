package com.ubot.plan.dto.request;

import com.ubot.common.enums.MasterStatus;
import jakarta.validation.constraints.*;

public record AdminPlanStatusUpdateRequestDto(
    @NotNull MasterStatus status
) {
}
