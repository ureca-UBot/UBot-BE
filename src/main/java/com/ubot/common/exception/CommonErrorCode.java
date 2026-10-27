package com.ubot.common.exception;

import org.springframework.http.HttpStatus;

import com.ubot.common.ErrorCode;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/** 특정 도메인에 속하지 않는 공통 API 오류 코드입니다. */
@Getter
@RequiredArgsConstructor
public enum CommonErrorCode implements ErrorCode {
    INVALID_INPUT(HttpStatus.BAD_REQUEST, "G-001", "요청 값이 올바르지 않습니다."),
    INVALID_PARAMETER(HttpStatus.BAD_REQUEST, "G-002", "요청 파라미터가 올바르지 않습니다."),
    INVALID_REQUEST_BODY(HttpStatus.BAD_REQUEST, "G-003", "요청 본문을 읽을 수 없습니다."),
    RESOURCE_NOT_FOUND(HttpStatus.NOT_FOUND, "G-004", "요청한 정보를 찾을 수 없습니다."),
    INTERNAL_SERVER_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "G-005", "서버 오류가 발생했습니다.");

    private final HttpStatus status;
    private final String code;
    private final String message;
}
