package com.ubot.addon.dto.request;

import com.ubot.addon.enums.AddonCategory;

import jakarta.validation.constraints.*;

/** category를 비우면 ETC로 등록한다. */
public record AdminAddonServiceCreateRequestDto(
        @NotBlank @Pattern(regexp = "^[A-Z][A-Z0-9_]{0,49}$") String code,
        @NotBlank @Size(max = 150) String name,
        @Size(max = 10000) String description,
        @NotNull @PositiveOrZero Integer monthlyFee,
        AddonCategory category
) {
}
