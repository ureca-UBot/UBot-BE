package com.ubot.bundle.dto.response;

import com.ubot.common.dto.response.AdminMetadataResponseDto;
import com.ubot.common.enums.MasterStatus;
import com.ubot.bundle.entity.BundleProduct;

public record AdminBundleProductResponseDto(
        BundleProductResponseDto detail,
        MasterStatus status,
        AdminMetadataResponseDto metadata
) {

    public static AdminBundleProductResponseDto from(BundleProduct product) {
        return new AdminBundleProductResponseDto(
                BundleProductResponseDto.from(product),
                product.getStatus(),
                AdminMetadataResponseDto.from(product)
        );
    }
}
