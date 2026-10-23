package com.ubot.home.dto.request;

import jakarta.validation.constraints.*;

/** 상품 종류는 바꿀 수 없다. 인터넷은 speedMbps가 필요하고 IPTV는 비워야 한다. */
public record AdminHomeProductUpdateRequestDto(
        @NotBlank @Size(max = 150) String name,
        @Size(max = 10000) String description,
        @Positive Integer speedMbps,
        @NotNull @PositiveOrZero Integer monthlyFee
) {
}
