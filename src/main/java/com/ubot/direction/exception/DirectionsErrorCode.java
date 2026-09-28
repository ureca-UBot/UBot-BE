package com.ubot.direction.exception;

import org.springframework.http.HttpStatus;

import com.ubot.common.ErrorCode;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum DirectionsErrorCode implements ErrorCode {
    DIRECTIONS_ROUTE_NOT_FOUND(HttpStatus.NOT_FOUND, "DIRECTIONS-001", "경로를 찾을 수 없습니다."),
    DIRECTIONS_SERVICE_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "DIRECTIONS-002",
            "길찾기 서비스를 일시적으로 사용할 수 없습니다. 잠시 후 다시 시도해주세요."),
    DIRECTIONS_TIMEOUT(HttpStatus.GATEWAY_TIMEOUT, "DIRECTIONS-003", "길찾기 서비스 응답이 지연되고 있습니다."),
    DIRECTIONS_INVALID_CANDIDATE(HttpStatus.BAD_REQUEST, "DIRECTIONS-004",
            "대중교통 경로 후보만 도보 상세를 조회할 수 있습니다.");

    private final HttpStatus status;
    private final String code;
    private final String message;
}
