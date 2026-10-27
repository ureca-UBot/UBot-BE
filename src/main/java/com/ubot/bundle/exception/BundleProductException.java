package com.ubot.bundle.exception;

import com.ubot.common.GlobalException;

public class BundleProductException extends GlobalException {

    public BundleProductException(BundleProductErrorCode errorCode) {
        super(errorCode);
    }
}
