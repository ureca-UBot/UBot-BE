package com.ubot.prompt.exception;

import com.ubot.common.GlobalException;

/** 프롬프트 준비 상태나 입력 문제를 채팅 실패 응답으로 전달합니다. */
public class PromptException extends GlobalException {

    public PromptException(PromptErrorCode errorCode) {
        super(errorCode);
    }

    public PromptException(PromptErrorCode errorCode, Throwable cause) {
        super(errorCode);
        initCause(cause);
    }
}
