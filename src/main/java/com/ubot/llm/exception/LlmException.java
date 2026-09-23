package com.ubot.llm.exception;

import com.ubot.llm.enums.LlmErrorCode;
import lombok.Getter;

@Getter
public class LlmException extends RuntimeException {

    private final LlmErrorCode errorCode;

    public LlmException(LlmErrorCode errorCode) {
        this(errorCode, null);
    }

    public LlmException(LlmErrorCode errorCode, Throwable cause) {
        super(errorCode.getMessage(), cause);
        this.errorCode = errorCode;
    }
}
