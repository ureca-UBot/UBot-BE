package com.ubot.counsel.message.dto.response;

import java.time.Instant;

public record MessageResponseDto(long messageId, boolean mine, String senderRole, String content,
		long deliverySequence, Instant createdAt) {}
