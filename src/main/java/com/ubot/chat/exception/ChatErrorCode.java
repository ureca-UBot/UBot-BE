package com.ubot.chat.exception;

import org.springframework.http.HttpStatus;

import com.ubot.common.ErrorCode;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum ChatErrorCode implements ErrorCode {
	INVALID_CHAT_REQUEST(HttpStatus.BAD_REQUEST, "CHAT-001", "요청 값이 올바르지 않습니다."),
	LOGIN_REQUIRED(HttpStatus.UNAUTHORIZED, "CHAT-002", "로그인이 필요합니다."),
	ATTEMPT_NOT_FOUND(HttpStatus.NOT_FOUND, "CHAT-003", "본인의 답변 시도 기록을 찾을 수 없습니다."),
	PROCESSING(HttpStatus.CONFLICT, "CHAT-004", "이미 답변을 생성하고 있습니다."),
	ALREADY_SUCCEEDED(HttpStatus.CONFLICT, "CHAT-005", "이미 답변이 생성된 질문입니다."),
	LIMIT_REACHED(HttpStatus.CONFLICT, "CHAT-006", "최초 시도를 포함하여 최대 3회까지만 시도할 수 있습니다."),
	RETRY_NOT_ALLOWED(HttpStatus.CONFLICT, "CHAT-007", "벡터 검색 또는 LLM 내부 오류가 발생한 질문만 재시도할 수 있습니다."),
	STORAGE_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "CHAT-008", "채팅 기록을 저장할 수 없습니다."),
	TASK_START_FAILED(HttpStatus.SERVICE_UNAVAILABLE, "CHAT-009", "답변 생성 작업을 시작하지 못했습니다."),
	VECTOR_SEARCH_FAILED(HttpStatus.SERVICE_UNAVAILABLE, "CHAT-010", "벡터 검색 중 오류가 발생했습니다. 잠시 후 다시 시도해주세요."),
	INVALID_CHAT_RETRY_REQUEST(HttpStatus.BAD_REQUEST, "CHAT-011", "요청 값이 올바르지 않습니다."),
	NO_FAQ(HttpStatus.NOT_FOUND, "CHAT_NO_FAQ", "검색 결과가 없습니다."),
	INSUFFICIENT_FAQ(HttpStatus.NOT_FOUND, "CHAT_INSUFFICIENT_FAQ", "정확한 답변을 찾지 못했습니다."),
	PROMPT_NOT_READY(HttpStatus.SERVICE_UNAVAILABLE, "CHAT_PROMPT_NOT_READY", "답변 프롬프트가 준비되지 않았습니다."),
	INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "CHAT_INTERNAL_ERROR", "답변 생성 중 오류가 발생했습니다.");

	private final HttpStatus status;
	private final String code;
	private final String message;
}
