package com.ubot.bundle.dto.response;

import com.ubot.bundle.entity.BundleProduct;

public record BundleProductResponseDto(
        Long bundleProductId,
        String code,
        String name,
        String description,
        Integer discountAmount
) {

    public static BundleProductResponseDto from(BundleProduct product) {
        return new BundleProductResponseDto(
                product.getBundleProductId(),
                product.getCode(),
                product.getName(),
                product.getDescription(),
                product.getDiscountAmount()
        );
    }
}
