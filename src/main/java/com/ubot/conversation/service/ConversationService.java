package com.ubot.conversation.service;

import com.ubot.conversation.entity.Conversation;
import com.ubot.conversation.repository.ConversationRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class ConversationService {
	private final ConversationRepository conversationRepository;

	@Transactional
	public Long createGuestConversation() {
		return conversationRepository.save(Conversation.createGuest()).getId();
	}
}
