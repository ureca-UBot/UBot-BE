package com.ubot.subscription.dto.response;

import java.time.LocalDateTime;

import com.ubot.subscription.entity.SubscriptionBundle;
import com.ubot.subscription.enums.SubscriptionBundleStatus;

/**
 * group은 이용 중인 결합에만 채우고, 종료된 결합이나 그룹 없이 남은 기록은 null이다.
 * appliedDiscountAmount는 그룹이 있으면 그룹의 할인 판정(회선 수·홈 인터넷 가입)을 따르고, 없으면 저장된 값이다.
 */
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
		Integer appliedDiscountAmount = group != null && !group.discountApplied()
				? Integer.valueOf(0) : subscription.getAppliedDiscountAmount();
		return new SubscriptionBundleResponseDto(
				subscription.getSubscriptionBundleId(), subscription.getBundleProduct().getBundleProductId(),
				subscription.getProductNameSnapshot(), appliedDiscountAmount,
				subscription.getStatus(), subscription.getJoinedAt(), subscription.getEndedAt(), group);
	}
}
