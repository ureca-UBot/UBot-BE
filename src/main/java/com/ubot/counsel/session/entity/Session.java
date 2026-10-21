package com.ubot.counsel.session.entity;

import com.ubot.counsel.auth.model.Caller;
import com.ubot.counsel.common.exception.CounselException;
import com.ubot.user.enums.UserRole;
import java.time.Instant;
import java.util.UUID;

public record Session(UUID sessionId, long customerId, Long counselorId, String status,
		long assignmentVersion, long lastDeliveredSeq, Instant createdAt, Instant pausedAt, Instant endedAt,
		UUID conversationId, UUID handoffId) {
	public void requireParticipant(Caller caller) {
		boolean customer = caller.id() == customerId && (caller.role() == UserRole.USER);
		boolean counselor = counselorId != null && caller.id() == counselorId && (caller.role() == UserRole.COUNSELOR);
		if (!customer && !counselor) throw CounselException.forbidden();
	}
	public void requireActive() {
		if (!status.equals("ACTIVE")) throw CounselException.conflict("연결된 상담에서만 메시지를 보낼 수 있습니다.");
	}
}
