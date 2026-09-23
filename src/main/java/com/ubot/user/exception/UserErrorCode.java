package com.ubot.user.exception;

import org.springframework.http.HttpStatus;

import com.ubot.common.ErrorCode;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum UserErrorCode implements ErrorCode {
    USER_NOT_FOUND(HttpStatus.NOT_FOUND, "USER-001", "해당 User가 존재하지 않습니다."),
    LOGIN_FAILED(HttpStatus.UNAUTHORIZED, "USER-002", "이메일 혹은 비밀번호가 일치하지 않습니다."),
    EMAIL_ALREADY_EXISTS(HttpStatus.CONFLICT, "USER-003", "이메일이 이미 존재합니다."),
    PASSWORD_CONFIRM_MISMATCH(HttpStatus.BAD_REQUEST, "USER-004", "비밀번호 및 재확인이 일치하지 않습니다."),
    INVALID_SIGNUP_REQUEST(HttpStatus.BAD_REQUEST, "USER-005", "SignupRequestDto가 올바르지 않습니다."),
    INVALID_LOGIN_REQUEST(HttpStatus.BAD_REQUEST, "USER-006", "LoginRequestDto가 올바르지 않습니다."),
    INVALID_EMAIL_FORMAT(HttpStatus.BAD_REQUEST, "USER-007", "이메일 양식이 올바르지 않습니다."),
    INVALID_PASSWORD_FORMAT(HttpStatus.BAD_REQUEST, "USER-008", "비밀번호 양식이 올바르지 않습니다."),
    INVALID_BIRTHDATE_FORMAT(HttpStatus.BAD_REQUEST, "USER-009", "생일 입력이 올바르지 않습니다."),
    INVALID_NAME_FORMAT(HttpStatus.BAD_REQUEST, "USER-010", "이름 입력이 올바르지 않습니다."),
    INVALID_GENDER_FORMAT(HttpStatus.BAD_REQUEST, "USER-011", "성별 입력이 올바르지 않습니다."),
    INVALID_RESIDENCE_FORMAT(HttpStatus.BAD_REQUEST, "USER-012", "사는 지역 입력이 올바르지 않습니다."),
    INVALID_USER_UPDATE_REQUEST(HttpStatus.BAD_REQUEST, "USER-013", "수정할 항목이 없습니다.");

    private final HttpStatus status;
    private final String code;
    private final String message;
}
