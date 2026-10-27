package com.ubot.reservation.enums;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum ReservationPurpose {
	PURCHASE_CONSULTING("구매 상담"),
	PLAN_CHANGE("요금제 변경"),
	DEVICE_CHANGE("기기변경"),
	ACTIVATION("개통"),
	OWNERSHIP_TRANSFER("명의변경"),
	USIM_REPLACEMENT("유심 교체"),
	LOSS_DAMAGE_REPORT("분실·파손 접수");

	private final String displayName;
}
