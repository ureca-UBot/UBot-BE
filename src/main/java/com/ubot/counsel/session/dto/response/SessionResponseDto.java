package com.ubot.counsel.session.dto.response;

import com.ubot.counsel.session.entity.Session;
import java.time.Instant;
import java.util.UUID;

public record SessionResponseDto(UUID sessionId, long customerId, Long counselorId, String status,
		long assignmentVersion, long lastDeliveredSeq, Instant createdAt, Instant pausedAt, Instant endedAt,
		UUID conversationId, UUID handoffId) {
	public static SessionResponseDto from(Session session) {
		if (session == null) return null;
		return new SessionResponseDto(session.sessionId(), session.customerId(), session.counselorId(), session.status(),
				session.assignmentVersion(), session.lastDeliveredSeq(), session.createdAt(), session.pausedAt(), session.endedAt(),
				session.conversationId(), session.handoffId());
	}
}
