package com.ubot.counsel.common.exception;

import com.ubot.common.ErrorCode;
import org.springframework.http.HttpStatus;

public enum CounselErrorCode implements ErrorCode {
	INVALID_INPUT(400, "COUNSEL-001", "요청 형식 또는 범위를 확인해 주세요."),
	UNAUTHORIZED(401, "COUNSEL-002", "로그인이 필요합니다."),
	FORBIDDEN(403, "COUNSEL-003", "접근 권한이 없습니다."),
	SESSION_NOT_FOUND(404, "COUNSEL-004", "상담을 찾을 수 없습니다."),
	USER_NOT_FOUND(404, "COUNSEL-005", "사용자를 찾을 수 없습니다."),
	CONFLICT(409, "COUNSEL-006", "상담 상태를 확인해 주세요."),
	PAYLOAD_TOO_LARGE(413, "COUNSEL-007", "요청이 너무 큽니다."),
	RATE_LIMITED(429, "COUNSEL-008", "전송이 너무 잦습니다. 잠시 후 재시도해 주세요."),
	DISABLED(503, "COUNSEL-009", "현재 사람 상담을 이용할 수 없습니다."),
	AUTH_UNAVAILABLE(503, "COUNSEL-010", "로그인 확인 서버에 연결할 수 없습니다."),
	DATABASE_UNAVAILABLE(503, "COUNSEL-011", "상담 저장소에 연결할 수 없습니다. 잠시 후 재시도해 주세요."),
	INTERNAL_ERROR(500, "COUNSEL-012", "요청 처리에 실패했습니다."),
	SAFETY_SENTENCE_NOT_FOUND(404, "COUNSEL-013", "금지 문장을 찾을 수 없습니다."),
	SAFETY_SENTENCE_DUPLICATE(409, "COUNSEL-014", "동일한 금지 문장이 이미 등록되어 있습니다."),
	SAFETY_INDEX_UNAVAILABLE(503, "COUNSEL-015", "문장 색인에 실패했습니다. 상태를 확인한 후 다시 시도해 주세요."),
	SAFETY_PROFILE_NOT_READY(409, "COUNSEL-016", "현재 모델의 상담 필터 프로필을 먼저 등록해 주세요."),
	CONVERSATION_NOT_FOUND(404, "COUNSEL-017", "본인의 AI 대화를 찾을 수 없습니다."),
	HISTORY_UNAVAILABLE(503, "COUNSEL-018", "이전 대화를 가져오지 못했습니다. 잠시 후 다시 요청해 주세요.");

	private final HttpStatus status;
	private final String code;
	private final String message;

	CounselErrorCode(int status, String code, String message) {
		this.status = HttpStatus.valueOf(status);
		this.code = code;
		this.message = message;
	}
	@Override
	public HttpStatus getStatus() {
		return status;
	}
	@Override
	public String getCode() {
		return code;
	}
	@Override
	public String getMessage() {
		return message;
	}
}
