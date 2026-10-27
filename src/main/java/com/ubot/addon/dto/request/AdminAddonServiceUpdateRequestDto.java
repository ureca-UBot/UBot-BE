package com.ubot.addon.dto.request;

import com.ubot.addon.enums.AddonCategory;

import jakarta.validation.constraints.*;

/** category를 비우면 기존 값을 유지한다. */
public record AdminAddonServiceUpdateRequestDto(
        @NotBlank @Size(max = 150) String name,
        @Size(max = 10000) String description,
        @NotNull @PositiveOrZero Integer monthlyFee,
        AddonCategory category
) {
}
