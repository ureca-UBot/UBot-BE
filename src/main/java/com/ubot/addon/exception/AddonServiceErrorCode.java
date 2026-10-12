package com.ubot.addon.exception;

import org.springframework.http.HttpStatus;

import com.ubot.common.ErrorCode;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum AddonServiceErrorCode implements ErrorCode {
    PRODUCT_NOT_FOUND(HttpStatus.NOT_FOUND, "ADDON-001", "부가서비스 상품을 찾을 수 없습니다."),
    DUPLICATE_CODE(HttpStatus.CONFLICT, "ADDON-002", "이미 사용 중인 상품 코드입니다. 삭제된 상품의 코드도 다시 사용할 수 없습니다."),
    INVALID_PRODUCT(HttpStatus.BAD_REQUEST, "ADDON-003", "상품의 필드 조합이 올바르지 않습니다."),
    PRODUCT_IN_USE(HttpStatus.CONFLICT, "ADDON-004", "가입 이력이 있는 상품의 조건 변경과 삭제는 불가능합니다. 새 상품 등록 또는 판매 중지를 이용해 주세요.");

    private final HttpStatus status;
    private final String code;
    private final String message;
}
