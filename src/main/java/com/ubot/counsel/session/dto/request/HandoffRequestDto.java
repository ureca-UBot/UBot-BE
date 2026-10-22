package com.ubot.counsel.session.dto.request;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

// 이전 FE가 보내는 본문은 호환성을 위해 무시하며 이력의 근거로 사용하지 않음
@JsonIgnoreProperties("displayedTurns")
public record HandoffRequestDto(@NotNull UUID handoffId, UUID conversationId, UUID previousSessionId,
		@Size(max = 100) List<@NotNull @Positive Long> faqIds) {
	public HandoffRequestDto {
		faqIds = faqIds == null ? List.of() : Collections.unmodifiableList(new ArrayList<>(faqIds));
	}
	public HandoffRequestDto(UUID handoffId, UUID conversationId, List<Long> faqIds) {
		this(handoffId, conversationId, null, faqIds);
	}
}
