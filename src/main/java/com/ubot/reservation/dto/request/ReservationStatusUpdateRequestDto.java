package com.ubot.reservation.dto.request;

import com.ubot.reservation.enums.ReservationStatus;
import jakarta.validation.constraints.NotNull;

public record ReservationStatusUpdateRequestDto(
		@NotNull ReservationStatus status
) {
}
