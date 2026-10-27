package com.ubot.prompt.exception;

import org.springframework.http.HttpStatus;

import com.ubot.common.ErrorCode;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * 프롬프트 구성 오류입니다.
 * 템플릿이 준비되지 않은 것은 운영 설정 문제라 503으로, 입력 누락·FAQ 이상은 앞 단계에서 걸렀어야 할 서버 오류라 500으로 둡니다.
 */
@Getter
@RequiredArgsConstructor
public enum PromptErrorCode implements ErrorCode {
	PROMPT_NOT_READY(HttpStatus.SERVICE_UNAVAILABLE, "PROMPT-001", "답변 프롬프트가 준비되지 않았습니다."),
	PROMPT_INPUT_MISSING(HttpStatus.INTERNAL_SERVER_ERROR, "PROMPT-002", "답변 생성에 필요한 입력이 없습니다."),
	PROMPT_FAQ_INVALID(HttpStatus.INTERNAL_SERVER_ERROR, "PROMPT-003", "검색된 FAQ 정보를 확인할 수 없습니다.");

	private final HttpStatus status;
	private final String code;
	private final String message;
}
