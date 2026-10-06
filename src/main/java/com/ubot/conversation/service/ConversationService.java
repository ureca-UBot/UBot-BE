package com.ubot.conversation.service;

import com.ubot.chat.repository.AnswerAttemptsHistoryRepository;
import com.ubot.chat.repository.QuestionLogRepository;
import com.ubot.conversation.entity.Conversation;
import com.ubot.conversation.repository.ConversationRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class ConversationService {
	private final ConversationRepository conversationRepository;
	private final AnswerAttemptsHistoryRepository answerAttemptsHistoryRepository;
	private final QuestionLogRepository questionLogRepository;

	@Transactional
	public Long createGuestConversation() {
		return conversationRepository.save(Conversation.createGuest()).getId();
	}

	@Transactional
	public boolean claimGuestConversation(Long conversationId, Long userId) {
		Conversation conversation = conversationRepository.findConversationForLock(conversationId).orElse(null);
		if (conversation == null || !conversation.isGuest()) {
			return false;
		}
		conversation.assignToMember(userId);
		conversationRepository.flush();
		answerAttemptsHistoryRepository.assignGuestAttemptsToUser(conversationId, userId);
		questionLogRepository.assignGuestQuestionLogsToUser(conversationId, userId);
		return true;
	}
}
