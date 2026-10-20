package com.ubot.counsel.history.service;

import com.ubot.conversation.exception.ConversationErrorCode;
import com.ubot.conversation.exception.ConversationException;
import com.ubot.conversation.repository.ConversationRepository;
import com.ubot.counsel.auth.model.Caller;
import com.ubot.counsel.history.dto.response.ConversationTimelineResponseDto;
import com.ubot.counsel.history.repository.ConversationTimelineRepository;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class ConversationTimelineService {
	private final ConversationRepository conversationRepository;
	private final ConversationTimelineRepository timelineRepository;

	@Transactional(readOnly = true)
	public List<ConversationTimelineResponseDto> getTimeline(Caller caller, UUID conversationId, long afterSequence, int limit) {
		caller.require("USER");
		if (afterSequence < 0 || limit < 1 || limit > 100) throw new IllegalArgumentException();
		conversationRepository.findByPublicIdAndUserId(conversationId, caller.id())
				.orElseThrow(() -> new ConversationException(ConversationErrorCode.CONVERSATION_NOT_FOUND));
		return timelineRepository.findTimeline(conversationId, afterSequence, limit);
	}
}
