package com.ubot.bundle.dto.response;

import com.ubot.bundle.entity.BundleProduct;
import com.ubot.bundle.enums.BundleType;

public record BundleProductResponseDto(
        Long bundleProductId,
        String code,
        String name,
        String description,
        Integer discountAmount,
        BundleType bundleType,
        Integer lineCount,
        Long homeProductId
) {

    public static BundleProductResponseDto from(BundleProduct product) {
        return new BundleProductResponseDto(
                product.getBundleProductId(),
                product.getCode(),
                product.getName(),
                product.getDescription(),
                product.getDiscountAmount(),
                product.getBundleType(),
                product.getLineCount(),
                product.getHomeProduct() == null ? null : product.getHomeProduct().getHomeProductId()
        );
    }
}
