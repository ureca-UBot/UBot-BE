package com.ubot.guardreview.model;

/** 실제 검사 결과와 관리자 분석 후보를 구분합니다. SKIPPED는 PASS가 아닙니다. */
public final class ReviewCheckModels {

	private ReviewCheckModels() {
	}

	public enum Origin { INPUT_CODE, INPUT_LLM, OUTPUT_CODE, OUTPUT_LLM, UNKNOWN }
	public enum Outcome { PASS, FILTERED, REJECTED, REVIEW, ERROR, SKIPPED, UNKNOWN }
}
