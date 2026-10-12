package com.ubot.subscription.exception;

import com.ubot.common.GlobalException;

public class SubscriptionException extends GlobalException {
	public SubscriptionException(SubscriptionErrorCode errorCode) {
		super(errorCode);
	}
}
