package com.ubot.subscription.dto.request;

import java.time.LocalDate;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;

/** 내 회선 상품 가입·변경 요청. */
public final class SubscriptionRequestDtos {

	private SubscriptionRequestDtos() {
	}

	public record PlanChangeRequestDto(@NotNull @Positive Long planId) {
	}

	public record AddonSubscribeRequestDto(@NotNull @Positive Long addonServiceId) {
	}

	public record RoamingScheduleRequestDto(
			@NotNull @Positive Long roamingProductId,
			@NotNull LocalDate startDate,
			@NotNull LocalDate endDate) {
	}

	public record BundleJoinRequestDto(@NotNull @Positive Long bundleProductId) {
	}

	/** 하이픈은 있어도 없어도 된다. */
	public record BundleMemberAddRequestDto(
			@NotBlank @Pattern(regexp = "^[0-9-]{9,20}$") String phoneNumber) {
	}
}
