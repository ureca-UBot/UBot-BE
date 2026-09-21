package com.ubot.common.exception;

import com.ubot.common.ErrorCode;
import com.ubot.common.GlobalException;

public class ChatException extends GlobalException {
    public ChatException(ErrorCode errorCode) {
        super(errorCode);
    }
}
