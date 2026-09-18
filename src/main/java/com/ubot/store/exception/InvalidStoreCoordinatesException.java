package com.ubot.store.exception;

import com.ubot.common.ErrorCode;
import com.ubot.common.GlobalException;

public class InvalidStoreCoordinatesException extends GlobalException {

    public InvalidStoreCoordinatesException() {
        super(ErrorCode.INVALID_STORE_COORDINATES);
    }
}
