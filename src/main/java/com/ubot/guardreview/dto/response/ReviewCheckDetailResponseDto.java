package com.ubot.guardreview.dto.response;

import java.util.List;
import java.util.Map;

public record ReviewCheckDetailResponseDto(ReviewCheckResponseDto check, String answer, String finalAnswer,
		String explanation, Map<String, Object> context, List<ReviewCheckReassessmentResponseDto> reassessments) {
	public ReviewCheckDetailResponseDto(ReviewCheckResponseDto check, String answer, String finalAnswer,
			String explanation, Map<String, Object> context) {
		this(check, answer, finalAnswer, explanation, context, List.of());
	}
}
