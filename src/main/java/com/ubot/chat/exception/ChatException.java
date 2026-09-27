package com.ubot.chat.exception;

import com.ubot.common.GlobalException;

public class ChatException extends GlobalException {
    public ChatException(ChatErrorCode errorCode) {
        super(errorCode);
    }
}
