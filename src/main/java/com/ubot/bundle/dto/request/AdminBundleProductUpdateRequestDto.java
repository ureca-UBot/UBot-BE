package com.ubot.bundle.dto.request;

import com.ubot.bundle.enums.BundleType;

import jakarta.validation.constraints.*;

/** bundleType, lineCount, homeProductId를 비우면 기존 값을 유지한다. */
public record AdminBundleProductUpdateRequestDto(
        @NotBlank @Size(max = 150) String name,
        @Size(max = 10000) String description,
        @NotNull @PositiveOrZero Integer discountAmount,
        BundleType bundleType,
        @Min(1) @Max(10) Integer lineCount,
        @Positive Long homeProductId
) {
}
