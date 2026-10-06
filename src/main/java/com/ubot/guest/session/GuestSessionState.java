package com.ubot.guest.session;

import java.io.Serializable;

public class GuestSessionState implements Serializable {
	private static final long serialVersionUID = 1L;

	private volatile Long conversationId;

	public Long getConversationId() {
		return conversationId;
	}

	void setConversationId(Long conversationId) {
		this.conversationId = conversationId;
	}
}
