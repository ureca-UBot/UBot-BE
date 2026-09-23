package com.ubot.chat.mvp.exception;

import com.ubot.common.ErrorCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

@Getter
@RequiredArgsConstructor
public enum ChatMvpErrorCode implements ErrorCode {
    LOGIN_REQUIRED(HttpStatus.UNAUTHORIZED, "CHAT-101", "로그인이 필요합니다."),
    NOT_FOUND(HttpStatus.NOT_FOUND, "CHAT-102", "상담 세션 또는 질문 기록을 찾을 수 없습니다."),
    KEY_CONFLICT(HttpStatus.CONFLICT, "CHAT-103", "해당 질문에 서버가 발급한 멱등키와 일치하지 않습니다."),
    PROCESSING(HttpStatus.CONFLICT, "CHAT-104", "이미 답변을 생성하고 있습니다. 해당 질문의 처리 상태를 조회해주세요."),
    ALREADY_SUCCEEDED(HttpStatus.CONFLICT, "CHAT-105", "이미 답변이 생성된 질문입니다."),
    LIMIT_REACHED(HttpStatus.CONFLICT, "CHAT-106", "최초 시도를 포함하여 최대 3회까지만 시도할 수 있습니다."),
    NOT_RETRYABLE(HttpStatus.CONFLICT, "CHAT-107", "재시도로 해결할 수 없는 요청입니다. 실패 안내를 확인해주세요."),
    STORAGE_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "CHAT-108", "채팅 기록 저장소가 준비되지 않았거나 연결할 수 없습니다."),
    BUSY(HttpStatus.SERVICE_UNAVAILABLE, "CHAT-109", "답변 생성 요청이 많습니다. 잠시 후 다시 시도해주세요."),
    INVALID_REQUEST(HttpStatus.BAD_REQUEST, "CHAT-110", "질문 또는 요청 키가 올바르지 않습니다.");

    private final HttpStatus status;
    private final String code;
    private final String message;
}
