package com.ubot.plan.dto.response;

import com.ubot.plan.entity.Plan;
import com.ubot.plan.enums.NetworkType;
import com.ubot.plan.enums.PlanTargetGroup;

public record PlanSummaryResponseDto(
    Long planId,
    String planCode,
    String name,
    NetworkType networkType,
    PlanTargetGroup targetGroup,
    Integer monthlyFee,
    Long dataAmountMb,
    boolean dataUnlimited,
    Integer exhaustedSpeedKbps,
    Integer voiceMinutes,
    boolean voiceUnlimited,
    Integer smsCount,
    boolean smsUnlimited
) {

    public static PlanSummaryResponseDto from(Plan product) {
        return new PlanSummaryResponseDto(
                product.getPlanId(),
                product.getPlanCode(),
                product.getName(),
                product.getNetworkType(),
                product.getTargetGroup(),
                product.getMonthlyFee(),
                product.getDataAmountMb(),
                product.isDataUnlimited(),
                product.getExhaustedSpeedKbps(),
                product.getVoiceMinutes(),
                product.isVoiceUnlimited(),
                product.getSmsCount(),
                product.isSmsUnlimited()
        );
    }
}
