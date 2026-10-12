package com.ubot.subscription.dto.response;

import java.time.LocalDateTime;

import com.ubot.subscription.entity.SubscriptionAddon;
import com.ubot.subscription.enums.SubscriptionAddonStatus;

public record SubscriptionAddonResponseDto(
		Long subscriptionAddonId,
		Long addonServiceId,
		String name,
		Integer monthlyFee,
		SubscriptionAddonStatus status,
		LocalDateTime subscribedAt,
		LocalDateTime canceledAt
) {
	public static SubscriptionAddonResponseDto from(SubscriptionAddon subscription) {
		return new SubscriptionAddonResponseDto(
				subscription.getSubscriptionAddonId(), subscription.getAddonService().getAddonServiceId(),
				subscription.getServiceNameSnapshot(), subscription.getMonthlyFeeSnapshot(),
				subscription.getStatus(), subscription.getSubscribedAt(), subscription.getCanceledAt());
	}
}
