package com.ubot.bundle.dto.request;

import jakarta.validation.constraints.*;

public record AdminBundleProductUpdateRequestDto(
        @NotBlank @Size(max = 150) String name,
        @Size(max = 10000) String description,
        @NotNull @PositiveOrZero Integer discountAmount
) {
}
