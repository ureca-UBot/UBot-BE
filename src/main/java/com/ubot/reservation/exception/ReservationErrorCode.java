package com.ubot.reservation.exception;

import com.ubot.common.ErrorCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

@Getter
@RequiredArgsConstructor
public enum ReservationErrorCode implements ErrorCode {
	RESERVATION_NOT_FOUND(HttpStatus.NOT_FOUND, "RSV-001", "예약을 찾을 수 없습니다."),
	RESERVATION_SLOT_UNAVAILABLE(HttpStatus.CONFLICT, "RSV-002", "이미 예약된 시간입니다."),
	INVALID_VISIT_TIME(HttpStatus.BAD_REQUEST, "RSV-003", "예약할 수 없는 방문 시간입니다."),
	RESERVATION_LIMIT_EXCEEDED(HttpStatus.CONFLICT, "RSV-004", "진행 중인 예약은 최대 3건까지 가능합니다."),
	RESERVATION_NOT_CANCELABLE(HttpStatus.CONFLICT, "RSV-005", "취소할 수 없는 예약입니다."),
	INVALID_STATUS_CHANGE(HttpStatus.CONFLICT, "RSV-006", "변경할 수 없는 예약 상태입니다.");

	private final HttpStatus status;
	private final String code;
	private final String message;
}
