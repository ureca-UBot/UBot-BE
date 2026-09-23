package com.ubot.auth.exception;

import org.springframework.http.HttpStatus;

import com.ubot.common.ErrorCode;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum JwtErrorCode implements ErrorCode {
    REFRESH_TOKEN_NOT_FOUND(HttpStatus.UNAUTHORIZED, "JWT-001", "해당 RefreshToken이 존재하지 않습니다."),
    REFRESH_TOKEN_EXPIRED(HttpStatus.UNAUTHORIZED, "JWT-002", "해당 RefreshToken은 만료된 토큰입니다."),
    INVALID_REFRESH_TOKEN_REQUEST(HttpStatus.BAD_REQUEST, "JWT-003", "Request의 RefreshToken이 올바르지 않습니다."),
    INVALID_ACCESS_TOKEN(HttpStatus.UNAUTHORIZED, "JWT-004", "유효하지 않은 Access Token입니다."),
    DELETED_USER_TOKEN(HttpStatus.UNAUTHORIZED, "JWT-005", "삭제된 유저의 토큰입니다."),
    DUPLICATED_CREATE_REFRESH_TOKEN(HttpStatus.CONFLICT, "JWT-006", "이미 RefreshToken이 생성되었습니다."),
    EXPIRED_ACCESS_TOKEN(HttpStatus.UNAUTHORIZED, "JWT-007", "해당 AccessToken은 만료된 토큰입니다.");

    private final HttpStatus status;
    private final String code;
    private final String message;
}
