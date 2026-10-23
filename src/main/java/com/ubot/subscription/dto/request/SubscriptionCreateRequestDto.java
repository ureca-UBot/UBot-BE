package com.ubot.subscription.dto.request;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;

/** 새 회선의 요금제와 원하는 번호. 번호는 하이픈이 있어도 없어도 되고, 비우면 서버가 배정한다. */
public record SubscriptionCreateRequestDto(
		@NotNull @Positive Long planId,
		@Pattern(regexp = "^010-?[0-9]{4}-?[0-9]{4}$") String phoneNumber
) {
}
