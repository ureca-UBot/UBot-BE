package com.ubot.direction.exception;

import com.ubot.common.GlobalException;

public class DirectionsException extends GlobalException {
    public DirectionsException(DirectionsErrorCode errorCode) {
        super(errorCode);
    }
}
