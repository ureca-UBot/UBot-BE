package com.ubot.plan.dto.response;

import com.ubot.common.dto.response.AdminMetadataResponseDto;
import com.ubot.common.enums.MasterStatus;
import com.ubot.plan.entity.Plan;

public record AdminPlanResponseDto(
        PlanDetailResponseDto detail,
        MasterStatus status,
        AdminMetadataResponseDto metadata
) {

    public static AdminPlanResponseDto from(Plan product) {
        return new AdminPlanResponseDto(
                PlanDetailResponseDto.from(product),
                product.getStatus(),
                AdminMetadataResponseDto.from(product)
        );
    }
}
