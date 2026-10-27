package com.ubot.bundle.dto.request;

import com.ubot.bundle.enums.BundleType;

import jakarta.validation.constraints.*;

/** bundleType을 비우면 ETC로 등록한다. lineCount를 비우면 회선 수 제한이 없다. homeProductId는 홈 결합의 인터넷 상품이다. */
public record AdminBundleProductCreateRequestDto(
        @NotBlank @Pattern(regexp = "^[A-Z][A-Z0-9_]{0,49}$") String code,
        @NotBlank @Size(max = 150) String name,
        @Size(max = 10000) String description,
        @NotNull @PositiveOrZero Integer discountAmount,
        BundleType bundleType,
        @Min(1) @Max(10) Integer lineCount,
        @Positive Long homeProductId
) {
}
