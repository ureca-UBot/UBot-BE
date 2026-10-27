package com.ubot.home.exception;

import com.ubot.common.GlobalException;

public class HomeProductException extends GlobalException {

    public HomeProductException(HomeProductErrorCode errorCode) {
        super(errorCode);
    }
}
