package com.ubot.notice.exception;

import org.springframework.http.HttpStatus;

import com.ubot.common.ErrorCode;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum NoticeErrorCode implements ErrorCode {
    NOTICE_NOT_FOUND(HttpStatus.NOT_FOUND, "NOTICE-001", "공지를 찾을 수 없습니다."),
    INVALID_ENDS_AT(HttpStatus.BAD_REQUEST, "NOTICE-002", "노출 종료 시각은 현재 이후여야 합니다.");

    private final HttpStatus status;
    private final String code;
    private final String message;
}