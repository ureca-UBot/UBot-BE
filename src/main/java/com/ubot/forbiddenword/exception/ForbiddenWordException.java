package com.ubot.forbiddenword.exception;

import com.ubot.common.GlobalException;

public class ForbiddenWordException extends GlobalException {
	public ForbiddenWordException(ForbiddenWordErrorCode errorCode) {
		super(errorCode);
	}

	public ForbiddenWordException(ForbiddenWordErrorCode errorCode, String message) {
		super(errorCode, message);
	}
}
