package com.ubot.prompt.exception;

/** 프롬프트 준비 상태나 입력 문제를 채팅 응답으로 전달합니다. */
public class PromptException extends RuntimeException {

    public PromptException(String message) {
        super(message);
    }

    public PromptException(String message, Throwable cause) {
        super(message, cause);
    }
}
