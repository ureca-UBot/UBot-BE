package com.ubot.inquiry.exception;

import com.ubot.common.GlobalException;

public class InquiryException extends GlobalException {
    public InquiryException(InquiryErrorCode errorCode) {
        super(errorCode);
    }
}
