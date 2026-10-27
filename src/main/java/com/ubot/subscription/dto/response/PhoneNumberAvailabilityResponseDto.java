package com.ubot.subscription.dto.response;

/** 조회 시점의 사용 가능 여부이며, 개통 시에는 DB 유일 조건으로 다시 확인합니다. */
public record PhoneNumberAvailabilityResponseDto(String phoneNumber, boolean available) {
}
