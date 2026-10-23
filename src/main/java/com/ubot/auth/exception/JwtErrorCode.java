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
    EXPIRED_ACCESS_TOKEN(HttpStatus.UNAUTHORIZED, "JWT-007", "해당 AccessToken은 만료된 토큰입니다."),
    REFRESH_TOKEN_REUSED(HttpStatus.UNAUTHORIZED, "JWT-008", "이미 사용된 RefreshToken입니다. 다시 로그인해주세요."),
    REVOKED_ACCESS_TOKEN(HttpStatus.UNAUTHORIZED, "JWT-009", "로그아웃된 AccessToken입니다. 다시 로그인해주세요."),
    // 로그아웃 여부를 확인하지 못한 상태. 토큰이 무효라는 뜻이 아니므로 401이 아닌 503으로 재시도를 유도함
    REVOCATION_CHECK_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "JWT-010", "로그인 상태를 확인할 수 없습니다. 잠시 후 다시 시도해주세요.");

    private final HttpStatus status;
    private final String code;
    private final String message;
}
