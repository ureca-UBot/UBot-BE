package com.ubot.common;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.micrometer.observation.autoconfigure.ObservationProperties;
import org.springframework.http.HttpStatus;

/** 공통 API 오류 코드와 이에 대응하는 HTTP 상태 및 기본 메시지입니다. */
@Getter
@RequiredArgsConstructor
public enum ErrorCode {
    // GlobalException
    INVALID_INPUT(HttpStatus.BAD_REQUEST, "G-001", "요청 값이 올바르지 않습니다."),
    INVALID_PARAMETER(HttpStatus.BAD_REQUEST, "G-002", "요청 파라미터가 올바르지 않습니다."),
    INVALID_REQUEST_BODY(HttpStatus.BAD_REQUEST, "G-003", "요청 본문을 읽을 수 없습니다."),
    RESOURCE_NOT_FOUND(HttpStatus.NOT_FOUND, "G-004", "요청한 정보를 찾을 수 없습니다."),
    INTERNAL_SERVER_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "G-005", "서버 오류가 발생했습니다."),



    // UserException
    USER_NOT_FOUND(HttpStatus.NOT_FOUND, "USER_001", "해당 User가 존재하지 않습니다."),

    // FaqException
    FAQ_NOT_FOUND(HttpStatus.NOT_FOUND, "FAQ-001", "해당 FAQ ID를 가진 FAQ가 존재하지 않습니다."),
    FAQ_VECTOR_CREATE_FAILURE(HttpStatus.BAD_REQUEST, "FAQ-002", "FAQ VECTOR 생성에 실패했습니다."),
    FAQ_CATEGORY_NOT_FOUND(HttpStatus.NOT_FOUND, "FAQ-003", "FAQ Category가 존재하지 않습니다."),
    FAQ_CATEGORY_EXIST(HttpStatus.CONFLICT, "FAQ-004", "이미 존재하는 카테고리 명입니다.");

    private final HttpStatus status;
    private final String code;
    private final String message;
}
