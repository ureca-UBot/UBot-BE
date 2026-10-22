package com.ubot.guardreview.exception;

import com.ubot.common.GlobalException;

public class ReviewException extends GlobalException {
	public ReviewException(ReviewErrorCode code) {
		super(code);
	}
}
