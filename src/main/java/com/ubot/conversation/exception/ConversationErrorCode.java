package com.ubot.conversation.exception;

import com.ubot.common.ErrorCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

@Getter
@RequiredArgsConstructor
public enum ConversationErrorCode implements ErrorCode {
	CONVERSATION_NOT_FOUND(HttpStatus.NOT_FOUND, "CONV-001", "대화를 찾을 수 없습니다."),
	CONVERSATION_IN_PROGRESS(HttpStatus.CONFLICT, "CONV-002", "답변 생성 중인 대화는 삭제할 수 없습니다."),
	CONVERSATION_HAS_COUNSEL_HISTORY(HttpStatus.CONFLICT, "CONV-003", "사람 상담 이력이 있는 대화는 삭제할 수 없습니다.");

	private final HttpStatus status;
	private final String code;
	private final String message;
}
