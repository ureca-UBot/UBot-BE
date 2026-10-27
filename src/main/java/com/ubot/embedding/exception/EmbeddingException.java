package com.ubot.embedding.exception;

import com.ubot.common.GlobalException;

public class EmbeddingException extends GlobalException {
    public EmbeddingException(EmbeddingErrorCode errorCode) {
        super(errorCode);
    }
}