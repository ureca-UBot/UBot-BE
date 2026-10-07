package com.ubot.reservation.entity;

import com.ubot.reservation.enums.ReservationPurpose;
import com.ubot.reservation.enums.ReservationStatus;
import com.ubot.store.entity.Store;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(name = "store_reservations")
public class StoreReservation {
	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	@Column(name = "reservation_id")
	private Long reservationId;

	@Column(name = "user_id")
	private Long userId;

	@ManyToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "store_id")
	private Store store;

	@Enumerated(EnumType.STRING)
	@Column(name = "purpose")
	private ReservationPurpose purpose;

	@Column(name = "visit_at")
	private LocalDateTime visitAt;

	@Enumerated(EnumType.STRING)
	@Column(name = "status")
	private ReservationStatus status;

	@Column(name = "created_at")
	private LocalDateTime createdAt;

	@Column(name = "updated_at")
	private LocalDateTime updatedAt;

	@Column(name = "canceled_at")
	private LocalDateTime canceledAt;

	public static StoreReservation create(Long userId, Store store, ReservationPurpose purpose, LocalDateTime visitAt){
		LocalDateTime now = LocalDateTime.now();
		StoreReservation reservation = new StoreReservation();
		reservation.userId = userId;
		reservation.store = store;
		reservation.purpose = purpose;
		reservation.visitAt = visitAt;
		reservation.status = ReservationStatus.RESERVED;
		reservation.createdAt = now;
		reservation.updatedAt = now;
		return reservation;
	}

	public void updateStatus(ReservationStatus status){
		LocalDateTime now = LocalDateTime.now();
		this.status = status;
		this.updatedAt = now;
		if(status == ReservationStatus.CANCELED){
			this.canceledAt = now;
		}
	}
}
