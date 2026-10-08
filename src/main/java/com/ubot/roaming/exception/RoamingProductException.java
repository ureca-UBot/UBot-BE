package com.ubot.roaming.exception;

import com.ubot.common.GlobalException;

public class RoamingProductException extends GlobalException {

    public RoamingProductException(RoamingProductErrorCode errorCode) {
        super(errorCode);
    }
}
