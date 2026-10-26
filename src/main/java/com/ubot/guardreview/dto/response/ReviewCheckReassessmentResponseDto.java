package com.ubot.guardreview.dto.response;

import com.ubot.guardreview.model.ReviewReassessment;
import java.time.LocalDateTime;

public record ReviewCheckReassessmentResponseDto(long runId, String status, LocalDateTime createdAt,
		LocalDateTime finishedAt, ReviewReassessment result) {
}
