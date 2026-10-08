package com.ubot.roaming.dto.response;

import com.ubot.roaming.entity.RoamingProduct;

public record RoamingProductResponseDto(
        Long roamingProductId,
        String code,
        String name,
        String description,
        Integer dailyFee,
        Long dataAmountMb,
        String country
) {

    public static RoamingProductResponseDto from(RoamingProduct product) {
        return new RoamingProductResponseDto(
                product.getRoamingProductId(),
                product.getCode(),
                product.getName(),
                product.getDescription(),
                product.getDailyFee(),
                product.getDataAmountMb(),
                product.getCountry()
        );
    }
}
