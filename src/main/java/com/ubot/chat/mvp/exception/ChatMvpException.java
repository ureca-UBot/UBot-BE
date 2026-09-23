package com.ubot.chat.mvp.exception;

import com.ubot.common.GlobalException;

public class ChatMvpException extends GlobalException {
    public ChatMvpException(ChatMvpErrorCode code) { super(code); }
}
