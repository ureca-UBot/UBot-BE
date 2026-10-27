package com.ubot.unanswered.exception;

import com.ubot.common.GlobalException;

public class UnansweredException extends GlobalException {
	public UnansweredException(UnansweredErrorCode errorCode){
		super(errorCode);
	}
}
