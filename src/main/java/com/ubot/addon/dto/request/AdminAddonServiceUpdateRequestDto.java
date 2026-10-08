package com.ubot.addon.dto.request;

import jakarta.validation.constraints.*;

public record AdminAddonServiceUpdateRequestDto(
        @NotBlank @Size(max = 150) String name,
        @Size(max = 10000) String description,
        @NotNull @PositiveOrZero Integer monthlyFee
) {
}
