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
	// 아래 메시지는 채팅 실패 시 화면에 그대로 보이므로 내부 용어(LLM) 없이 씁니다.
	LLM_SERVICE_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "LLM-003", "답변 서버에 연결할 수 없습니다. 잠시 후 다시 시도해주세요."),
	LLM_TIMEOUT(HttpStatus.GATEWAY_TIMEOUT, "LLM-004", "답변 생성 시간이 초과되었습니다. 잠시 후 다시 시도해주세요."),
	LLM_RESPONSE_INVALID(HttpStatus.INTERNAL_SERVER_ERROR, "LLM-005", "답변을 만들지 못했습니다. 잠시 후 다시 시도해주세요."),
	LLM_OUTPUT_REJECTED(HttpStatus.UNPROCESSABLE_CONTENT, "LLM-006", "검사 후 제공할 수 있는 답변이 없습니다. 질문을 바꾸어 다시 입력해주세요."),
	LLM_BUSY(HttpStatus.TOO_MANY_REQUESTS, "LLM-007", "요청이 많아 답변이 늦어지고 있습니다. 잠시 후 다시 시도해주세요.");

	private final HttpStatus status;
	private final String code;
	private final String message;
}
