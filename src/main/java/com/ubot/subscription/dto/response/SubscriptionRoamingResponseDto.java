package com.ubot.subscription.dto.response;

import java.time.LocalDate;
import java.time.LocalDateTime;

import com.ubot.subscription.entity.SubscriptionRoaming;
import com.ubot.subscription.enums.SubscriptionRoamingStatus;

public record SubscriptionRoamingResponseDto(
		Long subscriptionRoamingId,
		Long roamingProductId,
		String name,
		String country,
		Integer dailyFee,
		Long dataAmountMb,
		LocalDate startDate,
		LocalDate endDate,
		SubscriptionRoamingStatus status,
		LocalDateTime canceledAt
) {
	public static SubscriptionRoamingResponseDto from(SubscriptionRoaming subscription) {
		return new SubscriptionRoamingResponseDto(
				subscription.getSubscriptionRoamingId(), subscription.getRoamingProduct().getRoamingProductId(),
				subscription.getProductNameSnapshot(), subscription.getRoamingProduct().getCountry(),
				subscription.getDailyFeeSnapshot(), subscription.getDataAmountMbSnapshot(),
				subscription.getStartDate(), subscription.getEndDate(), subscription.getStatus(),
				subscription.getCanceledAt());
	}
}
