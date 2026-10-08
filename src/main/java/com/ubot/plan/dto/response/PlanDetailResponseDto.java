package com.ubot.plan.dto.response;

import com.ubot.plan.entity.Plan;

public record PlanDetailResponseDto(
        PlanSummaryResponseDto summary,
        String description,
        Long tetheringAmountMb,
        Integer minAge,
        Integer maxAge
) {

    public static PlanDetailResponseDto from(Plan product) {
        return new PlanDetailResponseDto(
                PlanSummaryResponseDto.from(product),
                product.getDescription(),
                product.getTetheringAmountMb(),
                product.getMinAge(),
                product.getMaxAge()
        );
    }
}
