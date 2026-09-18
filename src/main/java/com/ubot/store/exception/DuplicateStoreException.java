package com.ubot.store.exception;

import com.ubot.common.ErrorCode;
import com.ubot.common.GlobalException;

public class DuplicateStoreException extends GlobalException {

    public DuplicateStoreException() {
        super(ErrorCode.DUPLICATE_STORE);
    }
}
