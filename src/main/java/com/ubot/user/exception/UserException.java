package com.ubot.user.exception;

import com.ubot.common.GlobalException;

public class UserException extends GlobalException {
	public UserException(UserErrorCode errorCode) {
		super(errorCode);
	}

	public UserException(UserErrorCode errorCode, String message) {
		super(errorCode, message);
	}
}
