package com.ubot.addon.dto.response;

import com.ubot.addon.entity.AddonService;
import com.ubot.addon.enums.AddonCategory;

public record AddonServiceResponseDto(
        Long addonServiceId,
        String code,
        String name,
        String description,
        Integer monthlyFee,
        AddonCategory category
) {

    public static AddonServiceResponseDto from(AddonService product) {
        return new AddonServiceResponseDto(
                product.getAddonServiceId(),
                product.getCode(),
                product.getName(),
                product.getDescription(),
                product.getMonthlyFee(),
                product.getCategory()
        );
    }
}
