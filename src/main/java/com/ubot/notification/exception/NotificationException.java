package com.ubot.notification.exception;

import com.ubot.common.GlobalException;

public class NotificationException extends GlobalException {
	public NotificationException(NotificationErrorCode errorCode){
		super(errorCode);
	}
}
