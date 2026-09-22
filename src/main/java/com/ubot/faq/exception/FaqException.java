package com.ubot.faq.exception;

import com.ubot.common.GlobalException;

public class FaqException extends GlobalException {
	public FaqException(FaqErrorCode errorCode) {
		super(errorCode);
	}

	public FaqException(FaqErrorCode errorCode, String message) {
		super(errorCode, message);
	}
}
