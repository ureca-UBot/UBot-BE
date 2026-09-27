package com.ubot.faq.exception;

import org.springframework.http.HttpStatus;

import com.ubot.common.ErrorCode;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum FaqErrorCode implements ErrorCode {
    FAQ_NOT_FOUND(HttpStatus.NOT_FOUND, "FAQ-001", "해당 FAQ ID를 가진 FAQ가 존재하지 않습니다."),
    FAQ_VECTOR_CREATE_FAILURE(HttpStatus.BAD_REQUEST, "FAQ-002", "FAQ VECTOR 생성에 실패했습니다."),
    FAQ_CATEGORY_NOT_FOUND(HttpStatus.NOT_FOUND, "FAQ-003", "FAQ Category가 존재하지 않습니다."),
    FAQ_CATEGORY_EXIST(HttpStatus.CONFLICT, "FAQ-004", "이미 존재하는 카테고리 명입니다."),
    FAQ_CATEGORY_SAME_NAME(HttpStatus.BAD_REQUEST, "FAQ-005", "이전 카테고리명과 후 카테고리명이 같습니다."),
    FAQ_CATEGORY_IN_USE(HttpStatus.BAD_REQUEST, "FAQ-006", "해당 카테고리를 사용중인 FAQ가 있습니다.");

    private final HttpStatus status;
    private final String code;
    private final String message;
}
