package com.ubot.counsel.session.dto.request;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;

public record HandoffRequestDto(@NotNull UUID handoffId, UUID conversationId, UUID previousSessionId,
		@NotNull @Size(max = 1000) List<@NotNull @Valid Turn> displayedTurns) {
	public HandoffRequestDto(UUID handoffId, UUID conversationId, List<Turn> displayedTurns) {
		this(handoffId, conversationId, null, displayedTurns);
	}
	public record Turn(@NotBlank @Size(max = 4000) String question, @NotNull @Size(max = 30000) String answer) {}
}
