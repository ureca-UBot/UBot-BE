package com.ubot.subscription.exception;

import org.springframework.http.HttpStatus;

import com.ubot.common.ErrorCode;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum SubscriptionErrorCode implements ErrorCode {
	SUBSCRIPTION_NOT_FOUND(HttpStatus.NOT_FOUND, "SUB-001", "조회 가능한 회선을 찾을 수 없습니다.");

	private final HttpStatus status;
	private final String code;
	private final String message;
}
