package com.ubot.common.exception;

import com.ubot.common.ErrorCode;
import com.ubot.common.GlobalException;

public class UserException extends GlobalException {
	public UserException(ErrorCode errorCode) {
		super(errorCode);
	}

	public UserException(ErrorCode errorCode, String message) {
		super(errorCode, message);
	}
}
