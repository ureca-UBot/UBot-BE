package com.ubot.auth.exception;

import com.ubot.common.GlobalException;

public class MyJwtException extends GlobalException {
	public MyJwtException(JwtErrorCode errorCode) {
		super(errorCode);
	}

	public MyJwtException(JwtErrorCode errorCode, String message) {
		super(errorCode, message);
	}
}
