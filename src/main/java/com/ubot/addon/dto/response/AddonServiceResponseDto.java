package com.ubot.addon.dto.response;

import com.ubot.addon.entity.AddonService;

public record AddonServiceResponseDto(
        Long addonServiceId,
        String code,
        String name,
        String description,
        Integer monthlyFee
) {

    public static AddonServiceResponseDto from(AddonService product) {
        return new AddonServiceResponseDto(
                product.getAddonServiceId(),
                product.getCode(),
                product.getName(),
                product.getDescription(),
                product.getMonthlyFee()
        );
    }
}
