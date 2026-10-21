package com.ubot.counsel.admin.dto.response;

import java.time.Instant;
import java.util.UUID;

public record SafetyEventResponseDto(long id, Instant createdAt, UUID sessionId, String senderRole, String decision,
		String category, Double score, double threshold, String modelName, String profileVersion,
		String ruleVersion, String errorCode, String adminReview) {}
