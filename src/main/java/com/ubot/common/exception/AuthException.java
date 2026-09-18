package com.ubot.common.exception;

import com.ubot.common.ErrorCode;
import com.ubot.common.GlobalException;

public class AuthException extends GlobalException {
	public AuthException(ErrorCode errorCode) {
		super(errorCode);
	}

	public AuthException(ErrorCode errorCode, String message) {
		super(errorCode, message);
	}
}