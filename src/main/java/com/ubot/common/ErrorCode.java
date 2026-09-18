package com.ubot.common;

import org.springframework.http.HttpStatus;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/** 공통 API 오류 코드와 이에 대응하는 HTTP 상태 및 기본 메시지입니다. */
@Getter
@RequiredArgsConstructor
public enum ErrorCode {

    INVALID_INPUT(HttpStatus.BAD_REQUEST, "INVALID_INPUT", "요청 값이 올바르지 않습니다."),
    INVALID_PARAMETER(HttpStatus.BAD_REQUEST, "INVALID_PARAMETER", "요청 파라미터가 올바르지 않습니다."),
    INVALID_REQUEST_BODY(HttpStatus.BAD_REQUEST, "INVALID_REQUEST_BODY", "요청 본문을 읽을 수 없습니다."),
    RESOURCE_NOT_FOUND(HttpStatus.NOT_FOUND, "RESOURCE_NOT_FOUND", "요청한 정보를 찾을 수 없습니다."),
    INTERNAL_SERVER_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_SERVER_ERROR", "서버 오류가 발생했습니다."),

    // ── 매장 도메인 ──
    STORE_NOT_FOUND(HttpStatus.NOT_FOUND, "STORE_NOT_FOUND", "매장을 찾을 수 없습니다."),
    SERVICE_TYPE_NOT_FOUND(HttpStatus.NOT_FOUND, "SERVICE_TYPE_NOT_FOUND", "서비스 유형을 찾을 수 없습니다."),
    INVALID_MAP_BOUNDS(HttpStatus.BAD_REQUEST, "INVALID_MAP_BOUNDS", "지도 영역 좌표가 올바르지 않습니다."),

    // ── 임베딩 도메인 ──
    EMBEDDING_SERVICE_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "EM-001",
            "임베딩 서버 응답이 없습니다. 잠시 후 다시 시도해주세요."),
    EMBEDDING_RESPONSE_INVALID(HttpStatus.INTERNAL_SERVER_ERROR, "EM-002",
            "임베딩 서버 응답 형식이 올바르지 않습니다."),
    EMBEDDING_TIMEOUT(HttpStatus.GATEWAY_TIMEOUT, "EM-003", "임베딩 서버 응답이 지연되고 있습니다.");

    private final HttpStatus status;
    private final String code;
    private final String message;
}