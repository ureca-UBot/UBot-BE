package com.ubot.home.dto.response;

import com.ubot.common.dto.response.AdminMetadataResponseDto;
import com.ubot.common.enums.MasterStatus;
import com.ubot.home.entity.HomeProduct;

public record AdminHomeProductResponseDto(
        HomeProductResponseDto detail,
        MasterStatus status,
        AdminMetadataResponseDto metadata
) {

    public static AdminHomeProductResponseDto from(HomeProduct product) {
        return new AdminHomeProductResponseDto(
                HomeProductResponseDto.from(product),
                product.getStatus(),
                AdminMetadataResponseDto.from(product)
        );
    }
}
