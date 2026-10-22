package com.ubot.subscription.dto.request;

import java.time.LocalDate;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

public record RoamingScheduleRequestDto(
		@NotNull @Positive Long roamingProductId,
		@NotNull LocalDate startDate,
		@NotNull LocalDate endDate) {
}
