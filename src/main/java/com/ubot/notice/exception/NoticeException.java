package com.ubot.notice.exception;

import com.ubot.common.GlobalException;

public class NoticeException extends GlobalException {

    public NoticeException(NoticeErrorCode errorCode) {
        super(errorCode);
    }
}