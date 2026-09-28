package com.ubot.llm.exception;

import org.springframework.http.HttpStatus;

import com.ubot.common.ErrorCode;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum LlmErrorCode implements ErrorCode {
	LLM_REQUEST_INVALID(HttpStatus.BAD_REQUEST, "LLM-001", "LLM 입력 메시지가 올바르지 않습니다."),
	LLM_MODEL_NOT_CONFIGURED(HttpStatus.INTERNAL_SERVER_ERROR, "LLM-002", "답변 생성에 사용할 LLM 모델이 설정되지 않았습니다."),
	LLM_SERVICE_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "LLM-003", "LLM 서버에 연결할 수 없습니다. 잠시 후 다시 시도해주세요."),
	LLM_TIMEOUT(HttpStatus.GATEWAY_TIMEOUT, "LLM-004", "LLM 응답 시간이 초과되었습니다. 잠시 후 다시 시도해주세요."),
	LLM_RESPONSE_INVALID(HttpStatus.INTERNAL_SERVER_ERROR, "LLM-005", "LLM 서버에서 올바른 답변을 받지 못했습니다.");

	private final HttpStatus status;
	private final String code;
	private final String message;
}
