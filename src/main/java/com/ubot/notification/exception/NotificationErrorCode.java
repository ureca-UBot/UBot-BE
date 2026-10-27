package com.ubot.notification.exception;

import com.ubot.common.ErrorCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

@Getter
@RequiredArgsConstructor
public enum NotificationErrorCode implements ErrorCode {
	NOTIFICATION_NOT_FOUND(HttpStatus.NOT_FOUND, "NOTI-001", "알림을 찾을 수 없습니다.");

	private final HttpStatus status;
	private final String code;
	private final String message;
}
