package com.ubot.home.dto.response;

import java.time.LocalDateTime;

import com.ubot.home.entity.HomeSubscription;
import com.ubot.home.enums.HomeProductType;
import com.ubot.home.enums.HomeSubscriptionStatus;

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
        return new HomeSubscriptionResponseDto(
                subscription.getHomeSubscriptionId(),
                subscription.getHomeProduct().getHomeProductId(),
                subscription.getProductType(),
                subscription.getProductNameSnapshot(),
                subscription.getMonthlyFeeSnapshot(),
                subscription.getStatus(),
                subscription.getJoinedAt(),
                subscription.getEndedAt()
        );
    }
}
