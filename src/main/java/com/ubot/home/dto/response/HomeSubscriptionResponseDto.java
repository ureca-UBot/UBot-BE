package com.ubot.home.dto.response;

import java.time.LocalDateTime;

import com.ubot.home.entity.HomeSubscription;
import com.ubot.home.enums.HomeProductType;
import com.ubot.home.enums.HomeSubscriptionStatus;

import lombok.Builder;

@Builder
public record HomeSubscriptionResponseDto(
        Long homeSubscriptionId,
        Long homeProductId,
        HomeProductType productType,
        String name,
        Integer monthlyFee,
        HomeSubscriptionStatus status,
        LocalDateTime joinedAt,
        LocalDateTime endedAt
) {

    public static HomeSubscriptionResponseDto from(HomeSubscription subscription) {
        return HomeSubscriptionResponseDto.builder()
                .homeSubscriptionId(subscription.getHomeSubscriptionId())
                .homeProductId(subscription.getHomeProduct().getHomeProductId())
                .productType(subscription.getProductType())
                .name(subscription.getProductNameSnapshot())
                .monthlyFee(subscription.getMonthlyFeeSnapshot())
                .status(subscription.getStatus())
                .joinedAt(subscription.getJoinedAt())
                .endedAt(subscription.getEndedAt())
                .build();
    }
}
