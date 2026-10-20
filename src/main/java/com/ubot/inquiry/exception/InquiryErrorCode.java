package com.ubot.inquiry.exception;

import com.ubot.common.ErrorCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

@Getter
@RequiredArgsConstructor
public enum InquiryErrorCode implements ErrorCode {
    NOT_FOUND(HttpStatus.NOT_FOUND, "INQUIRY-001", "문의를 찾을 수 없습니다."),
    ALREADY_RESOLVED(HttpStatus.CONFLICT, "INQUIRY-002", "답변이 등록된 문의는 수정하거나 삭제할 수 없습니다."),
    INVALID_UPDATE(HttpStatus.BAD_REQUEST, "INQUIRY-003", "수정할 제목 또는 내용을 입력해 주세요.");

    private final HttpStatus status;
    private final String code;
    private final String message;
}
