package com.ubot.plan.dto.request;

import java.util.List;

import jakarta.validation.constraints.*;

/** 요금제에 포함된 부가서비스를 통째로 바꾼다. 빈 목록이면 모두 뺀다. */
public record AdminPlanIncludedAddonRequestDto(
        @NotNull @Size(max = 20) List<@NotNull @Positive Long> addonServiceIds
) {
}
