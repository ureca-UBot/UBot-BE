package com.ubot.reservation.dto.response;

import com.ubot.reservation.entity.StoreReservation;
import com.ubot.reservation.enums.ReservationPurpose;
import com.ubot.reservation.enums.ReservationStatus;
import com.ubot.store.entity.Store;

import java.time.LocalDateTime;

public record ReservationResponseDto(
		Long reservationId,
		Long userId,
		Long storeId,
		String storeName,
		String storeAddress,
		String storePhoneNumber,
		ReservationPurpose purpose,
		String purposeName,
		LocalDateTime visitAt,
		ReservationStatus status,
		LocalDateTime createdAt,
		LocalDateTime canceledAt
) {
	public static ReservationResponseDto from(StoreReservation reservation){
		Store store = reservation.getStore();
		return new ReservationResponseDto(
				reservation.getReservationId(),
				reservation.getUserId(),
				store.getStoreId(),
				store.getStoreName(),
				store.getAddress(),
				store.getPhoneNumber(),
				reservation.getPurpose(),
				reservation.getPurpose().getDisplayName(),
				reservation.getVisitAt(),
				reservation.getStatus(),
				reservation.getCreatedAt(),
				reservation.getCanceledAt()
		);
	}
}
