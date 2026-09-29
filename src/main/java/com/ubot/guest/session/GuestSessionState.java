package com.ubot.guest.session;

import java.io.Serializable;

/** 게스트 채팅에 필요한 최소 상태입니다. 질문·답변 본문은 담지 않고 DB에서 관리합니다. */
public class GuestSessionState implements Serializable {
	private static final long serialVersionUID = 1L;

	private Long conversationId;
	private int chatCount;

	public synchronized Long getConversationId() {
		return conversationId;
	}

	public synchronized void setConversationId(Long conversationId) {
		this.conversationId = conversationId;
	}

	public synchronized int getChatCount() {
		return chatCount;
	}

	public synchronized int increaseChatCount() {
		return ++chatCount;
	}
}
