package com.ubot.telemap.exception;

import com.ubot.common.GlobalException;

public class TelemapException extends GlobalException {

    public TelemapException(TelemapErrorCode errorCode) {
        super(errorCode);
    }
}
