package com.ubot.common;

import org.springframework.http.HttpStatus;

/** 도메인별 오류 코드가 구현해야 하는 공통 인터페이스입니다. 도메인 코드 충돌을 막기 위해 도메인마다 별도 enum으로 구현합니다. */
public interface ErrorCode {
    HttpStatus getStatus();
    String getCode();
    String getMessage();
}
