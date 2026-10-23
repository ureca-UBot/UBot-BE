package com.ubot.telemap.exception;

import org.springframework.http.HttpStatus;

import com.ubot.common.ErrorCode;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum TelemapErrorCode implements ErrorCode {
    QUESTION_LOG_NOT_FOUND(HttpStatus.NOT_FOUND, "TM-001", "급증 감지 대상 질문 로그가 존재하지 않습니다."),
    INVALID_OUTBOX_EVENT(HttpStatus.BAD_REQUEST, "TM-002", "급증 감지 Outbox 이벤트 형식이 올바르지 않습니다."),
    SURGING_ALERT_NOT_FOUND(HttpStatus.NOT_FOUND, "TM-003", "급증 알림 이력이 존재하지 않습니다."),
    SUBJECT_GENERATION_FAILED(HttpStatus.INTERNAL_SERVER_ERROR, "TM-004", "급증 알림 제목을 생성할 수 없습니다."),
    INVALID_CONFIGURATION(HttpStatus.INTERNAL_SERVER_ERROR, "TM-005", "급증 감지 설정이 올바르지 않습니다."),
    UNPROCESSED_ALERT(HttpStatus.BAD_REQUEST, "TM-006", "아직 라벨링되지 않은 알림입니다."),
    PROCESSED_ALERT(HttpStatus.CONFLICT,"TM-007", "이미 처리된 알림입니다.");

    private final HttpStatus status;
    private final String code;
    private final String message;
}
