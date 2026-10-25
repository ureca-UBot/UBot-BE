package com.ubot.guardreview.dto.response;

import com.ubot.guardreview.model.ReviewCheckModels.Origin;
import com.ubot.guardreview.model.ReviewCheckModels.Outcome;
import java.time.LocalDateTime;
import java.util.List;

public record ReviewCheckResponseDto(long id, Long attemptId, Origin origin, Outcome outcome,
		List<String> reasons, String question, String answerPreview, LocalDateTime createdAt,
		boolean detailsRetained) {
}
