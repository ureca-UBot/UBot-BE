package com.ubot.counsel.safety.entity;

import java.time.Instant;
import java.util.UUID;

public record SafetySentence(long id, String content, int severity, boolean enabled, long revision,
		Long indexedRevision, Long vectorId, UUID indexToken, boolean indexInProgress, String indexError,
		String model, String profile, Instant createdAt, Instant updatedAt) {
	public boolean currentIndex() {
		return indexedRevision != null && indexedRevision == revision;
	}
}
