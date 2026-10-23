package com.ubot.home.exception;

import com.ubot.common.GlobalException;

public class HomeSubscriptionException extends GlobalException {

    public HomeSubscriptionException(HomeSubscriptionErrorCode errorCode) {
        super(errorCode);
    }
}
