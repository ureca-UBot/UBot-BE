package com.ubot.counsel.history.dto.response;

import java.time.Instant;
import java.util.UUID;

public record ConversationTimelineResponseDto(
		long sequence,
		String id,
		UUID sessionId,
		String role,
		String content,
		String source,
		Instant createdAt,
		String cursor
) {}
