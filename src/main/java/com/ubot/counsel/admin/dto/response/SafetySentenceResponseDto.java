package com.ubot.counsel.admin.dto.response;

import com.ubot.counsel.safety.entity.SafetySentence;
import java.time.Instant;

public record SafetySentenceResponseDto(long sentenceId, String content, String category, int severity,
		boolean enabled, long revision, String indexStatus, boolean applied, Long indexedRevision,
		Long vectorId, String indexError, String model, String profile, Instant createdAt, Instant updatedAt) {
	public static SafetySentenceResponseDto from(SafetySentence sentence) {
		String status = sentence.indexInProgress() ? "INDEXING" : sentence.indexError() != null ? "FAILED"
				: sentence.currentIndex() ? "INDEXED" : "PENDING";
		return new SafetySentenceResponseDto(sentence.id(), sentence.content(), "INSULT", sentence.severity(),
				sentence.enabled(), sentence.revision(), status, sentence.enabled() && sentence.currentIndex(),
				sentence.indexedRevision(), sentence.vectorId(), sentence.indexError(), sentence.model(),
				sentence.profile(), sentence.createdAt(), sentence.updatedAt());
	}
}
