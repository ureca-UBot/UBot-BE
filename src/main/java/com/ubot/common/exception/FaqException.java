package com.ubot.common.exception;

import com.ubot.common.ErrorCode;

public class FaqException extends GlobalException{
	public FaqException(ErrorCode errorCode) {
		super(errorCode);
	}

	public FaqException(ErrorCode errorCode, String message) {
		super(errorCode, message);
	}
}
