package com.ubot.llm.exception;

import com.ubot.common.GlobalException;

public class LlmException extends GlobalException {
	public LlmException(LlmErrorCode errorCode) {
		super(errorCode);
	}

	public LlmException(LlmErrorCode errorCode, Throwable cause) {
		super(errorCode);
		initCause(cause);
	}
}
