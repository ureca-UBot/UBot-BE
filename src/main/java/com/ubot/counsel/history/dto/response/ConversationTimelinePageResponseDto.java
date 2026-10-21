package com.ubot.counsel.history.dto.response;

import java.util.List;

public record ConversationTimelinePageResponseDto(
		List<ConversationTimelineResponseDto> items, String nextCursor, boolean hasNext) {}
