package com.ubot.roaming.dto.request;

import jakarta.validation.constraints.*;

public record AdminRoamingProductUpdateRequestDto(
        @NotBlank @Size(max = 150) String name,
        @Size(max = 10000) String description,
        @NotNull @PositiveOrZero Integer dailyFee,
        @NotNull @PositiveOrZero Long dataAmountMb,
        @NotBlank @Size(max = 100) String country
) {
}
