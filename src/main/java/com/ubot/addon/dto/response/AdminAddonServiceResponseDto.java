package com.ubot.addon.dto.response;

import com.ubot.common.dto.response.AdminMetadataResponseDto;
import com.ubot.common.enums.MasterStatus;
import com.ubot.addon.entity.AddonService;

public record AdminAddonServiceResponseDto(
        AddonServiceResponseDto detail,
        MasterStatus status,
        AdminMetadataResponseDto metadata
) {

    public static AdminAddonServiceResponseDto from(AddonService product) {
        return new AdminAddonServiceResponseDto(
                AddonServiceResponseDto.from(product),
                product.getStatus(),
                AdminMetadataResponseDto.from(product)
        );
    }
}
