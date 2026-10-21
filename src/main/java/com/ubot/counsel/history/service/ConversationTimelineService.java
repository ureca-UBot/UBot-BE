package com.ubot.counsel.history.service;

import com.ubot.conversation.exception.ConversationErrorCode;
import com.ubot.conversation.exception.ConversationException;
import com.ubot.conversation.repository.ConversationRepository;
import com.ubot.counsel.auth.model.Caller;
import com.ubot.counsel.history.dto.response.ConversationTimelinePageResponseDto;
import com.ubot.counsel.history.dto.response.ConversationTimelineResponseDto;
import com.ubot.counsel.history.model.TimelineCursor;
import com.ubot.counsel.history.repository.ConversationTimelineRepository;
import com.ubot.user.enums.UserRole;
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
	public ConversationTimelinePageResponseDto getTimeline(Caller caller, UUID conversationId, String afterCursor, int limit) {
		caller.require(UserRole.USER);
		if (limit < 1 || limit > 100) throw new IllegalArgumentException();
		conversationRepository.findByPublicIdAndUserIdAndDeletedAtIsNull(conversationId, caller.id())
				.orElseThrow(() -> new ConversationException(ConversationErrorCode.CONVERSATION_NOT_FOUND));
		var rows = timelineRepository.findTimeline(conversationId, TimelineCursor.decode(afterCursor), limit + 1);
		boolean hasNext = rows.size() > limit;
		var items = List.copyOf(rows.subList(0, Math.min(rows.size(), limit)));
		return new ConversationTimelinePageResponseDto(items, hasNext ? items.getLast().cursor() : null, hasNext);
	}
}
