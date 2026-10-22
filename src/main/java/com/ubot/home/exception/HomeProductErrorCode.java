package com.ubot.home.exception;

import org.springframework.http.HttpStatus;

import com.ubot.common.ErrorCode;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum HomeProductErrorCode implements ErrorCode {
    PRODUCT_NOT_FOUND(HttpStatus.NOT_FOUND, "HOME-001", "인터넷/IPTV 상품을 찾을 수 없습니다."),
    DUPLICATE_CODE(HttpStatus.CONFLICT, "HOME-002", "이미 사용 중인 상품 코드입니다. 삭제된 상품의 코드도 다시 사용할 수 없습니다."),
    INVALID_PRODUCT(HttpStatus.BAD_REQUEST, "HOME-003", "상품의 필드 조합이 올바르지 않습니다."),
    PRODUCT_IN_USE(HttpStatus.CONFLICT, "HOME-004", "가입 이력이 있는 상품의 조건 변경과 삭제는 불가능합니다. 새 상품 등록 또는 판매 중지를 이용해 주세요."),
    SUBSCRIPTION_NOT_FOUND(HttpStatus.NOT_FOUND, "HOME-005", "이용 중인 인터넷/IPTV 가입을 찾을 수 없습니다."),
    ALREADY_SUBSCRIBED(HttpStatus.CONFLICT, "HOME-006", "같은 종류의 인터넷/IPTV 상품을 이미 이용 중입니다.");

    private final HttpStatus status;
    private final String code;
    private final String message;
}
