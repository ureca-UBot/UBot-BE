package com.myapp.common;

import lombok.Getter;

/**
 * 서비스 전반에서 사용할 비즈니스 예외입니다.
 * HTTP 표현 방식은 {@link GlobalExceptionHandler}가 담당합니다.
 */
@Getter
public class GlobalException extends RuntimeException {

    private final ErrorCode errorCode;

    public GlobalException(ErrorCode errorCode) {
        this(errorCode, errorCode.getMessage());
    }

    public GlobalException(ErrorCode errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }
}
