package com.ubot.unanswered.exception;

import com.ubot.common.ErrorCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

@Getter
@RequiredArgsConstructor
public enum UnansweredErrorCode implements ErrorCode {
	UNANSWERED_GROUP_NOT_FOUND(HttpStatus.NOT_FOUND, "UQ-001", "해당 미응답 질문 묶음이 존재하지 않습니다."),
	UNANSWERED_GROUP_ALREADY_RESOLVED(HttpStatus.CONFLICT, "UQ-002", "이미 FAQ가 등록된 미응답 질문 묶음입니다.");

	private final HttpStatus status;
	private final String code;
	private final String message;
}
