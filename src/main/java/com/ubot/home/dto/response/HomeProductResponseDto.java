package com.ubot.home.dto.response;

import com.ubot.home.entity.HomeProduct;
import com.ubot.home.enums.HomeProductType;

public record HomeProductResponseDto(
        Long homeProductId,
        String code,
        String name,
        String description,
        HomeProductType productType,
        Integer speedMbps,
        Integer monthlyFee
) {

    public static HomeProductResponseDto from(HomeProduct product) {
        return new HomeProductResponseDto(
                product.getHomeProductId(),
                product.getCode(),
                product.getName(),
                product.getDescription(),
                product.getProductType(),
                product.getSpeedMbps(),
                product.getMonthlyFee()
        );
    }
}
