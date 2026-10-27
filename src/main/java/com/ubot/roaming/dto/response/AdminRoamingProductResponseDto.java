package com.ubot.roaming.dto.response;

import com.ubot.common.dto.response.AdminMetadataResponseDto;
import com.ubot.common.enums.MasterStatus;
import com.ubot.roaming.entity.RoamingProduct;

public record AdminRoamingProductResponseDto(
        RoamingProductResponseDto detail,
        MasterStatus status,
        AdminMetadataResponseDto metadata
) {

    public static AdminRoamingProductResponseDto from(RoamingProduct product) {
        return new AdminRoamingProductResponseDto(
                RoamingProductResponseDto.from(product),
                product.getStatus(),
                AdminMetadataResponseDto.from(product)
        );
    }
}
