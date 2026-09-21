package com.ubot.store.exception;

import com.ubot.common.ErrorCode;
import com.ubot.common.GlobalException;

public class StoreNotFoundException extends GlobalException {

    public StoreNotFoundException() {
        super(ErrorCode.STORE_NOT_FOUND);
    }
}
