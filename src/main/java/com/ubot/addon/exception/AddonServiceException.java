package com.ubot.addon.exception;

import com.ubot.common.GlobalException;

public class AddonServiceException extends GlobalException {

    public AddonServiceException(AddonServiceErrorCode errorCode) {
        super(errorCode);
    }
}
