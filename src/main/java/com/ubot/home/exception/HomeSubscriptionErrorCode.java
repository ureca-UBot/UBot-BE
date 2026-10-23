package com.ubot.home.exception;

import org.springframework.http.HttpStatus;

import com.ubot.common.ErrorCode;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum HomeSubscriptionErrorCode implements ErrorCode {
    SUBSCRIPTION_NOT_FOUND(HttpStatus.NOT_FOUND, "HSUB-001", "이용 중인 인터넷/IPTV 가입을 찾을 수 없습니다."),
    ALREADY_SUBSCRIBED(HttpStatus.CONFLICT, "HSUB-002", "같은 종류의 인터넷/IPTV 상품을 이미 이용 중입니다.");

    private final HttpStatus status;
    private final String code;
    private final String message;
}
