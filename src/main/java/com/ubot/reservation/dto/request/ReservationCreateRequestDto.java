package com.ubot.reservation.dto.request;

import com.ubot.reservation.enums.ReservationPurpose;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.time.LocalDateTime;

public record ReservationCreateRequestDto(
		@NotNull @Positive Long storeId,
		@NotNull ReservationPurpose purpose,
		@NotNull LocalDateTime visitAt
) {
}
