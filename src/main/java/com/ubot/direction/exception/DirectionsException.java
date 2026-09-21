package com.ubot.direction.exception;

import com.ubot.common.ErrorCode;
import com.ubot.common.GlobalException;

public class DirectionsException extends GlobalException {

    public DirectionsException(ErrorCode errorCode) {
        super(errorCode);
    }
}
