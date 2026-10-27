package com.ubot.auth.exception;

import com.ubot.common.GlobalException;

public class AuthException extends GlobalException {
	public AuthException(AuthErrorCode errorCode) {
		super(errorCode);
	}

	public AuthException(AuthErrorCode errorCode, String message) {
		super(errorCode, message);
	}
}
