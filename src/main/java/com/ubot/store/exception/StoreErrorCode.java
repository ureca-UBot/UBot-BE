package com.ubot.store.exception;

import org.springframework.http.HttpStatus;

import com.ubot.common.ErrorCode;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum StoreErrorCode implements ErrorCode {
    STORE_NOT_FOUND(HttpStatus.NOT_FOUND,"STORE-001","매장을 찾을 수 없습니다."),
    DUPLICATE_STORE(HttpStatus.CONFLICT,"STORE-002","이미 등록된 매장입니다."),
    DELETED_STORE_ALREADY_EXISTS(HttpStatus.CONFLICT,"STORE-003","삭제된 동일 매장이 존재합니다. 기존 매장을 복구해주세요."),
    SERVICE_TYPE_NOT_FOUND(HttpStatus.NOT_FOUND,"STORE-004","서비스 유형을 찾을 수 없습니다."),
    INVALID_STORE_COORDINATES(HttpStatus.BAD_REQUEST,"STORE-005","위도와 경도는 함께 입력해야 합니다."),
    INVALID_MAP_BOUNDS(HttpStatus.BAD_REQUEST,"STORE-006","지도 영역 좌표가 올바르지 않습니다.");

    private final HttpStatus status;
    private final String code;
    private final String message;
}
