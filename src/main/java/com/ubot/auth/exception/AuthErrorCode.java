package com.ubot.auth.exception;

import org.springframework.http.HttpStatus;

import com.ubot.common.ErrorCode;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum AuthErrorCode implements ErrorCode {
    UNAUTHORIZED(HttpStatus.UNAUTHORIZED, "AUTH-001", "토큰이 없습니다. 인증되지 않은 사용자입니다."),
    FORBIDDEN(HttpStatus.FORBIDDEN, "AUTH-002", "접근 권한이 없습니다.");

    private final HttpStatus status;
    private final String code;
    private final String message;
}
