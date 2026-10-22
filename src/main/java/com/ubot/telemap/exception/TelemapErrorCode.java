package com.ubot.telemap.exception;

import org.springframework.http.HttpStatus;

import com.ubot.common.ErrorCode;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum TelemapErrorCode implements ErrorCode {
    QUESTION_LOG_NOT_FOUND(HttpStatus.NOT_FOUND, "TM-001", "급증 감지 대상 질문 로그가 존재하지 않습니다."),
    INVALID_OUTBOX_EVENT(HttpStatus.BAD_REQUEST, "TM-002", "급증 감지 Outbox 이벤트 형식이 올바르지 않습니다.");

    private final HttpStatus status;
    private final String code;
    private final String message;
}
