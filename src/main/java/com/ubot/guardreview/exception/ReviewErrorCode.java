package com.ubot.guardreview.exception;

import com.ubot.common.ErrorCode;
import org.springframework.http.HttpStatus;

public enum ReviewErrorCode implements ErrorCode {
	NOT_FOUND(HttpStatus.NOT_FOUND, "GUARD-REVIEW-001", "검토 기록이 없습니다."),
	CONFLICT(HttpStatus.CONFLICT, "GUARD-REVIEW-002", "내용이나 검사 기준이 변경됐습니다. 다시 검사해 주세요."),
	INVALID(HttpStatus.BAD_REQUEST, "GUARD-REVIEW-003", "검토 요청 값을 확인해 주세요."),
	TEST_REQUIRED(HttpStatus.CONFLICT, "GUARD-REVIEW-004", "현재 후보 전체에 대한 오차단 검사를 통과해야 합니다."),
	WARNING_ONLY(HttpStatus.BAD_REQUEST, "GUARD-REVIEW-005", "위험 문장은 경고 전용이며 금칙어로 적용할 수 없습니다."),
	ALREADY_REGISTERED(HttpStatus.CONFLICT, "GUARD-REVIEW-006", "이미 등록된 금칙어입니다. 기존 금칙어 화면에서 관리하거나 후보를 수정해 주세요."),
	CATALOG_CHANGED(HttpStatus.CONFLICT, "GUARD-REVIEW-007", "연결된 금칙어가 변경됐습니다. 후보를 수정한 뒤 다시 검사해 주세요."),
	WORD_TOO_SHORT(HttpStatus.BAD_REQUEST, "GUARD-REVIEW-008",
			"AI 금칙어 후보는 정규화 후 문자·숫자 3자 이상이어야 합니다. 짧은 표현은 경고로 검토하거나 더 구체적인 표현으로 수정해 주세요.");
	private final HttpStatus status;
	private final String code;
	private final String message;
	ReviewErrorCode(HttpStatus status, String code, String message) {
		this.status = status;
		this.code = code;
		this.message = message;
	}
	public HttpStatus getStatus() {
		return status;
	}
	public String getCode() {
		return code;
	}
	public String getMessage() {
		return message;
	}
}
