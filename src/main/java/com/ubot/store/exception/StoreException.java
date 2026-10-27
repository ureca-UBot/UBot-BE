package com.ubot.store.exception;

import com.ubot.common.GlobalException;

public class StoreException extends GlobalException {
    public StoreException(StoreErrorCode errorCode) {
        super(errorCode);
    }
}
