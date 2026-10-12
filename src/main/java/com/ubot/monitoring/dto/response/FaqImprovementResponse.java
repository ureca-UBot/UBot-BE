package com.ubot.monitoring.dto.response;

import com.ubot.monitoring.dto.model.improvelayer.FaqImprovementItem;
import java.time.LocalDateTime;
import java.util.List;

public record FaqImprovementResponse(
		LocalDateTime evaluatedAt,
		List<FaqImprovementItem> items
) {
}
