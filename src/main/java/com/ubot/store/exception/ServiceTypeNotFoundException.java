package com.ubot.store.exception;

import com.ubot.common.ErrorCode;
import com.ubot.common.GlobalException;

public class ServiceTypeNotFoundException extends GlobalException {

    public ServiceTypeNotFoundException() {
        super(ErrorCode.SERVICE_TYPE_NOT_FOUND);
    }
}
