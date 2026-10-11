package com.ubot.subscription.dto.response;

import java.time.LocalDateTime;

import com.ubot.subscription.entity.Subscription;
import com.ubot.subscription.enums.SimStatus;
import com.ubot.subscription.enums.SimType;
import com.ubot.subscription.enums.SubscriptionStatus;

public record SubscriptionResponseDto(
		Long subscriptionId,
		String phoneNumber,
		SubscriptionStatus status,
		LocalDateTime activatedAt,
		LocalDateTime terminatedAt,
		Long planId,
		String planName,
		SimType simType,
		String maskedSimIdentifier,
		SimStatus simStatus,
		LocalDateTime simActivatedAt,
		LocalDateTime simDeactivatedAt
) {
	public static SubscriptionResponseDto from(Subscription subscription) {
		return new SubscriptionResponseDto(
				subscription.getSubscriptionId(), subscription.getPhoneNumber(), subscription.getStatus(),
				subscription.getActivatedAt(), subscription.getTerminatedAt(),
				subscription.getPlan().getPlanId(), subscription.getPlan().getName(),
				subscription.getSimType(), maskIdentifier(subscription.getSimIdentifier()),
				subscription.getSimStatus(), subscription.getSimActivatedAt(), subscription.getSimDeactivatedAt());
	}

	private static String maskIdentifier(String identifier) {
		if (identifier == null) {
			return null;
		}
		if (identifier.length() <= 4) {
			return "*".repeat(identifier.length());
		}
		return "*".repeat(identifier.length() - 4) + identifier.substring(identifier.length() - 4);
	}
}
