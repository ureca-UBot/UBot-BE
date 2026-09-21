package com.ubot.store.exception;

import com.ubot.common.ErrorCode;
import com.ubot.common.GlobalException;

public class InvalidOriginException extends GlobalException {

    public InvalidOriginException() {
        super(ErrorCode.INVALID_ORIGIN);
    }
}
