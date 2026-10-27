package com.ubot.counsel.common.exception;

import com.ubot.common.ErrorCode;
import com.ubot.common.GlobalException;

public class CounselException extends GlobalException {
	public CounselException(ErrorCode code) { super(code); }
	public CounselException(ErrorCode code, String message) { super(code, message); }
	public static CounselException forbidden() {
		return new CounselException(CounselErrorCode.FORBIDDEN);
	}
	public static CounselException conflict(String message) {
		return new CounselException(CounselErrorCode.CONFLICT, message);
	}
}
