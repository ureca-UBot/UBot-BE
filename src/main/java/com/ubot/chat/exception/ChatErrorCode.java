package com.ubot.chat.exception;

import org.springframework.http.HttpStatus;

import com.ubot.common.ErrorCode;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum ChatErrorCode implements ErrorCode {
	INVALID_CHAT_REQUEST(HttpStatus.BAD_REQUEST, "CHAT-001", "요청 값이 올바르지 않습니다."),
	LOGIN_REQUIRED(HttpStatus.UNAUTHORIZED, "CHAT-101", "로그인이 필요합니다."),
	ATTEMPT_NOT_FOUND(HttpStatus.NOT_FOUND, "CHAT-102", "본인의 답변 시도 기록을 찾을 수 없습니다."),
	PROCESSING(HttpStatus.CONFLICT, "CHAT-104", "이미 답변을 생성하고 있습니다."),
	ALREADY_SUCCEEDED(HttpStatus.CONFLICT, "CHAT-105", "이미 답변이 생성된 질문입니다."),
	LIMIT_REACHED(HttpStatus.CONFLICT, "CHAT-106", "최초 시도를 포함하여 최대 3회까지만 시도할 수 있습니다."),
	RETRY_NOT_ALLOWED(HttpStatus.CONFLICT, "CHAT-107", "벡터 검색 또는 LLM 내부 오류가 발생한 질문만 재시도할 수 있습니다."),
	STORAGE_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "CHAT-108", "채팅 기록을 저장할 수 없습니다."),
	TASK_START_FAILED(HttpStatus.SERVICE_UNAVAILABLE, "CHAT-109", "답변 생성 작업을 시작하지 못했습니다."),
	VECTOR_SEARCH_FAILED(HttpStatus.SERVICE_UNAVAILABLE, "CHAT-110", "벡터 검색 중 오류가 발생했습니다. 잠시 후 다시 시도해주세요.");

	private final HttpStatus status;
	private final String code;
	private final String message;
}
