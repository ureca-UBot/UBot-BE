package com.ubot.chat.exception;

import com.ubot.chat.dto.response.ChatResponseDto;
import com.ubot.common.GlobalException;

public class ChatException extends GlobalException {
	private final ChatResponseDto response;

	public ChatException(ChatErrorCode errorCode) {
		this(errorCode, null);
	}

	public ChatException(ChatErrorCode errorCode, ChatResponseDto response) {
		super(errorCode);
		this.response = response;
	}

	public ChatResponseDto getResponse() {
		return response;
	}
}
