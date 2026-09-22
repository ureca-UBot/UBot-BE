package com.ubot.chat.exception;

import org.springframework.http.HttpStatus;

import com.ubot.common.ErrorCode;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum ChatErrorCode implements ErrorCode {
    INVALID_CHAT_REQUEST(HttpStatus.BAD_REQUEST, "CHAT-001", "요청 값이 올바르지 않습니다.");

    private final HttpStatus status;
    private final String code;
    private final String message;
}
