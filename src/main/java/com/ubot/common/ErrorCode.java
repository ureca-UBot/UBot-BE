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

    // ── 임베딩 도메인 ──
    EMBEDDING_SERVICE_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "EM-001",
                    "임베딩 서버 응답이 없습니다. 잠시 후 다시 시도해주세요."),
    EMBEDDING_RESPONSE_INVALID(HttpStatus.INTERNAL_SERVER_ERROR, "EM-002",
                    "임베딩 서버 응답 형식이 올바르지 않습니다."),
    EMBEDDING_TIMEOUT(HttpStatus.GATEWAY_TIMEOUT, "EM-003", "임베딩 서버 응답이 지연되고 있습니다."),

    // MyJwtException
    REFRESH_TOKEN_NOT_FOUND(HttpStatus.UNAUTHORIZED, "JWT-001", "해당 RefreshToken이 존재하지 않습니다."),
    REFRESH_TOKEN_EXPIRED(HttpStatus.UNAUTHORIZED, "JWT-002", "해당 RefreshToken은 만료된 토큰입니다."),
    INVALID_REFRESH_TOKEN_REQUEST(HttpStatus.BAD_REQUEST, "JWT-003", "Request의 RefreshToken이 올바르지 않습니다."),
    INVALID_ACCESS_TOKEN(HttpStatus.UNAUTHORIZED, "JWT-004", "유효하지 않은 Access Token입니다."),
    DELETED_USER_TOKEN(HttpStatus.UNAUTHORIZED, "JWT-005", "삭제된 유저의 토큰입니다."),
    DUPLICATED_CREATE_REFRESH_TOKEN(HttpStatus.CONFLICT, "JWT-006", "이미 RefreshToken이 생성되었습니다."),
    EXPIRED_ACCESS_TOKEN(HttpStatus.UNAUTHORIZED, "JWT-007", "해당 AccessToken은 만료된 토큰입니다."),

    // AuthException
    UNAUTHORIZED(HttpStatus.UNAUTHORIZED, "AUTH-001", "토큰이 없습니다. 인증되지 않은 사용자입니다."),
    FORBIDDEN(HttpStatus.FORBIDDEN, "AUTH-002", "접근 권한이 없습니다."),

    // UserException
    USER_NOT_FOUND(HttpStatus.NOT_FOUND, "USER-001", "해당 User가 존재하지 않습니다."),
    LOGIN_FAILED(HttpStatus.UNAUTHORIZED, "USER-002", "이메일 혹은 비밀번호가 일치하지 않습니다."),
    EMAIL_ALREADY_EXISTS(HttpStatus.CONFLICT, "USER-003", "이메일이 이미 존재합니다."),
    PASSWORD_CONFIRM_MISMATCH(HttpStatus.BAD_REQUEST, "USER-004", "비밀번호 및 재확인이 일치하지 않습니다."),
    INVALID_SIGNUP_REQUEST(HttpStatus.BAD_REQUEST,"USER-005", "SignupRequestDto가 올바르지 않습니다."),
    INVALID_LOGIN_REQUEST(HttpStatus.BAD_REQUEST, "USER-006", "LoginRequestDto가 올바르지 않습니다."),
    INVALID_EMAIL_FORMAT(HttpStatus.BAD_REQUEST, "USER-007", "이메일 양식이 올바르지 않습니다."),
    INVALID_PASSWORD_FORMAT(HttpStatus.BAD_REQUEST, "USER-008", "비밀번호 양식이 올바르지 않습니다."),
    INVALID_BIRTHDATE_FORMAT(HttpStatus.BAD_REQUEST, "USER-009", "생일 입력이 올바르지 않습니다."),
    INVALID_NAME_FORMAT(HttpStatus.BAD_REQUEST, "USER-010", "이름 입력이 올바르지 않습니다."),
    INVALID_GENDER_FORMAT(HttpStatus.BAD_REQUEST, "USER-011", "성별 입력이 올바르지 않습니다."),
    INVALID_RESIDENCE_FORMAT(HttpStatus.BAD_REQUEST, "USER-012", "사는 지역 입력이 올바르지 않습니다."),


    // ── 매장 도메인 ──
    STORE_NOT_FOUND(HttpStatus.NOT_FOUND, "STORE_NOT_FOUND", "매장을 찾을 수 없습니다."),
    DUPLICATE_STORE(HttpStatus.CONFLICT, "DUPLICATE_STORE", "이미 등록된 매장입니다."),
    DELETED_STORE_ALREADY_EXISTS(HttpStatus.CONFLICT, "DELETE_STORE_ALREADY_EXISTS", "삭제된 동일 매장이 존재합니다. 기존 매장을 복구해주세요."),
    SERVICE_TYPE_NOT_FOUND(HttpStatus.NOT_FOUND, "SERVICE_TYPE_NOT_FOUND", "서비스 유형을 찾을 수 없습니다."),
    INVALID_STORE_COORDINATES(HttpStatus.BAD_REQUEST, "INVALID_STORE_COORDINATES", "위도와 경도는 함께 입력해야 합니다."),
    INVALID_MAP_BOUNDS(HttpStatus.BAD_REQUEST, "INVALID_MAP_BOUNDS", "지도 영역 좌표가 올바르지 않습니다.");


    private final HttpStatus status;
    private final String code;
    private final String message;

}


