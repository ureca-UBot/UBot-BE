package com.ubot.conversation.exception;

import com.ubot.common.GlobalException;

public class ConversationException extends GlobalException {
	public ConversationException(ConversationErrorCode errorCode){
		super(errorCode);
	}
}
