package com.ubot.plan.exception;

import org.springframework.http.HttpStatus;

import com.ubot.common.ErrorCode;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum PlanErrorCode implements ErrorCode {
    PRODUCT_NOT_FOUND(HttpStatus.NOT_FOUND, "PLAN-001", "요금제을 찾을 수 없습니다."),
    DUPLICATE_CODE(HttpStatus.CONFLICT, "PLAN-002", "이미 사용 중인 상품 코드입니다. 삭제된 상품의 코드도 다시 사용할 수 없습니다."),
    INVALID_PRODUCT(HttpStatus.BAD_REQUEST, "PLAN-003", "상품의 필드 조합이 올바르지 않습니다.");

    private final HttpStatus status;
    private final String code;
    private final String message;
}
