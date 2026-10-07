package com.ubot.reservation.dto.response;

import java.time.LocalTime;

public record ReservationSlotResponseDto(
		LocalTime time,
		boolean available
) {
}
