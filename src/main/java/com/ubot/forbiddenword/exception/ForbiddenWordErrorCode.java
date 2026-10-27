package com.ubot.forbiddenword.exception;

import com.ubot.common.ErrorCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

@Getter
@RequiredArgsConstructor
public enum ForbiddenWordErrorCode implements ErrorCode {
	FORBIDDEN_WORD_NOT_FOUND(HttpStatus.NOT_FOUND, "FW-001", "해당 금지어가 존재하지 않습니다."),
	FORBIDDEN_WORD_EXIST(HttpStatus.CONFLICT, "FW-002", "이미 존재하는 금지어입니다."),
	FORBIDDEN_WORD_DETECTED(HttpStatus.BAD_REQUEST, "FW-003", "사용할 수 없는 표현이 포함되어 있습니다."),
	FORBIDDEN_WORD_REQUIRED(HttpStatus.BAD_REQUEST, "FW-004", "금지어를 입력해야 합니다."),
	FORBIDDEN_WORD_STATUS_REQUIRED(HttpStatus.BAD_REQUEST, "FW-005", "변경할 상태를 입력해야 합니다.");

	private final HttpStatus status;
	private final String code;
	private final String message;
}
