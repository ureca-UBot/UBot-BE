package com.ubot.counsel.admin.dto.response;

import com.ubot.counsel.safety.model.GuardDecision;

public record SafetyEvaluationResponseDto(String status, Long vectorId, Double score, String category, String errorCode) {
	public static SafetyEvaluationResponseDto from(GuardDecision decision) {
		return new SafetyEvaluationResponseDto(decision.status(), decision.vectorId(), decision.score(),
				decision.category(), decision.errorCode());
	}
}
