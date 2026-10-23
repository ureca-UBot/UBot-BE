package com.ubot.home.dto.response;

import com.ubot.common.dto.response.AdminMetadataResponseDto;
import com.ubot.common.enums.MasterStatus;
import com.ubot.home.entity.HomeProduct;

import lombok.Builder;

@Builder
public record AdminHomeProductResponseDto(
        HomeProductResponseDto detail,
        MasterStatus status,
        AdminMetadataResponseDto metadata
) {

    public static AdminHomeProductResponseDto from(HomeProduct product) {
        return AdminHomeProductResponseDto.builder()
                .detail(HomeProductResponseDto.from(product))
                .status(product.getStatus())
                .metadata(AdminMetadataResponseDto.from(product))
                .build();
    }
}
