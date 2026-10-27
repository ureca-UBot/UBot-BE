package com.ubot.plan.dto.request;

import com.ubot.plan.enums.NetworkType;
import com.ubot.plan.enums.PlanTargetGroup;
import jakarta.validation.constraints.*;

public record AdminPlanUpdateRequestDto(
    @NotBlank @Size(max = 150) String name,
    @Size(max = 10000) String description,
    @NotNull NetworkType networkType,
    @NotNull PlanTargetGroup targetGroup,
    @NotNull @PositiveOrZero Integer monthlyFee,
    @PositiveOrZero Long dataAmountMb,
    @NotNull Boolean dataUnlimited,
    @Positive Integer exhaustedSpeedKbps,
    @PositiveOrZero Integer voiceMinutes,
    @NotNull Boolean voiceUnlimited,
    @PositiveOrZero Integer smsCount,
    @NotNull Boolean smsUnlimited,
    @NotNull @PositiveOrZero Long tetheringAmountMb,
    @Min(0) @Max(120) Integer minAge,
    @Min(0) @Max(120) Integer maxAge
) {
}
