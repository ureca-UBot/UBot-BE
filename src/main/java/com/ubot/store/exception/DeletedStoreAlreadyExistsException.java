package com.ubot.store.exception;

import com.ubot.common.ErrorCode;
import com.ubot.common.GlobalException;

public class DeletedStoreAlreadyExistsException extends GlobalException {

    public DeletedStoreAlreadyExistsException() {
        super(ErrorCode.DELETED_STORE_ALREADY_EXISTS);
    }
}