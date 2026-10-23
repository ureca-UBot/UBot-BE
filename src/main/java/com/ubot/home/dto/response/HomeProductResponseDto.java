package com.ubot.home.dto.response;

import com.ubot.home.entity.HomeProduct;
import com.ubot.home.enums.HomeProductType;

import lombok.Builder;

@Builder
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
        return HomeProductResponseDto.builder()
                .homeProductId(product.getHomeProductId())
                .code(product.getCode())
                .name(product.getName())
                .description(product.getDescription())
                .productType(product.getProductType())
                .speedMbps(product.getSpeedMbps())
                .monthlyFee(product.getMonthlyFee())
                .build();
    }
}
