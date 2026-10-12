package com.ubot.subscription.dto.response;

import java.time.LocalDateTime;

import com.ubot.subscription.entity.SubscriptionBundle;
import com.ubot.subscription.enums.SubscriptionBundleStatus;

public record SubscriptionBundleResponseDto(
		Long subscriptionBundleId,
		Long bundleProductId,
		String name,
		Integer appliedDiscountAmount,
		SubscriptionBundleStatus status,
		LocalDateTime joinedAt,
		LocalDateTime endedAt
) {
	public static SubscriptionBundleResponseDto from(SubscriptionBundle subscription) {
		return new SubscriptionBundleResponseDto(
				subscription.getSubscriptionBundleId(), subscription.getBundleProduct().getBundleProductId(),
				subscription.getProductNameSnapshot(), subscription.getAppliedDiscountAmount(),
				subscription.getStatus(), subscription.getJoinedAt(), subscription.getEndedAt());
	}
}
