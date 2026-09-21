package com.ubot.common.exception;

import com.ubot.common.ErrorCode;
import com.ubot.common.GlobalException;

public class MyJwtException extends GlobalException {
	public MyJwtException(ErrorCode errorCode) {
		super(errorCode);
	}

	public MyJwtException(ErrorCode errorCode, String message) {
		super(errorCode, message);
	}
}