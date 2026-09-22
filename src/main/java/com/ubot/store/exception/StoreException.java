package com.ubot.store.exception;

import com.ubot.common.ErrorCode;
import com.ubot.common.GlobalException;

public class StoreException extends GlobalException {

    public StoreException(ErrorCode errorCode) {
        super(errorCode);
    }

    public StoreException(ErrorCode errorCode, String message) {
        super(errorCode, message);
    }
}