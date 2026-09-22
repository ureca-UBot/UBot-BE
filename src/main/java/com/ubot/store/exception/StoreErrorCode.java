package com.ubot.store.exception;

import org.springframework.http.HttpStatus;

import com.ubot.common.ErrorCode;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum StoreErrorCode implements ErrorCode {
    STORE_NOT_FOUND(HttpStatus.NOT_FOUND, "STORE-001", "매장을 찾을 수 없습니다."),
    SERVICE_TYPE_NOT_FOUND(HttpStatus.NOT_FOUND, "STORE-002", "서비스 유형을 찾을 수 없습니다."),
    INVALID_MAP_BOUNDS(HttpStatus.BAD_REQUEST, "STORE-003", "지도 영역 좌표가 올바르지 않습니다.");

    private final HttpStatus status;
    private final String code;
    private final String message;
}
