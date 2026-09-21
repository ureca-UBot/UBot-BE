package com.ubot.store.exception;

import com.ubot.common.ErrorCode;
import com.ubot.common.GlobalException;

public class InvalidMapBoundsException extends GlobalException {

    public InvalidMapBoundsException() {
        super(ErrorCode.INVALID_MAP_BOUNDS);
    }
}
