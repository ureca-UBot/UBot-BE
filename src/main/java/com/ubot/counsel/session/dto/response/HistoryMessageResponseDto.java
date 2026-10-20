package com.ubot.counsel.session.dto.response;

import com.fasterxml.jackson.annotation.JsonIgnore;
import java.util.UUID;

public record HistoryMessageResponseDto(long sequence, String role, String content, String source,
		@JsonIgnore UUID originSessionId, @JsonIgnore Long originSequence) {
	public HistoryMessageResponseDto(long sequence, String role, String content, String source) {
		this(sequence, role, content, source, null, null);
	}
}
