package com.ubot.embedding.exception;

import com.ubot.common.ErrorCode;
import com.ubot.common.GlobalException;

public class EmbeddingException extends GlobalException {
    public EmbeddingException(ErrorCode errorCode) {
        super(errorCode);
    }
}