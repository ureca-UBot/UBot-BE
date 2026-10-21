package com.ubot.counsel.safety.model;

public record GuardDecision(String status, Long vectorId, Double score, String category, String errorCode) {
	public static GuardDecision failed(String code) {
		return new GuardDecision("CHECK_FAILED", null, null, null, code);
	}
}
