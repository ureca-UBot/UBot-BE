package com.ubot.home.dto.request;

import com.ubot.home.enums.HomeProductType;

import jakarta.validation.constraints.*;

/** 인터넷은 speedMbps가 필요하고 IPTV는 비워야 한다. */
public record AdminHomeProductCreateRequestDto(
        @NotBlank @Pattern(regexp = "^[A-Z][A-Z0-9_]{0,49}$") String code,
        @NotBlank @Size(max = 150) String name,
        @Size(max = 10000) String description,
        @NotNull HomeProductType productType,
        @Positive Integer speedMbps,
        @NotNull @PositiveOrZero Integer monthlyFee
) {
}
