package com.ubot.subscription.dto.response;

import java.time.LocalDateTime;

import com.ubot.subscription.entity.SubscriptionBundle;
import com.ubot.subscription.enums.SubscriptionBundleStatus;

/** group은 이용 중인 결합에만 채우고, 종료된 결합이나 그룹 없이 남은 기록은 null이다. */
public record SubscriptionBundleResponseDto(
		Long subscriptionBundleId,
		Long bundleProductId,
		String name,
		Integer appliedDiscountAmount,
		SubscriptionBundleStatus status,
		LocalDateTime joinedAt,
		LocalDateTime endedAt,
		BundleGroupResponseDto group
) {
	public static SubscriptionBundleResponseDto from(SubscriptionBundle subscription, BundleGroupResponseDto group) {
		return new SubscriptionBundleResponseDto(
				subscription.getSubscriptionBundleId(), subscription.getBundleProduct().getBundleProductId(),
				subscription.getProductNameSnapshot(), subscription.getAppliedDiscountAmount(),
				subscription.getStatus(), subscription.getJoinedAt(), subscription.getEndedAt(), group);
	}
}
