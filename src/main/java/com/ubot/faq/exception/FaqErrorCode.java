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
    FAQ_CATEGORY_IN_USE(HttpStatus.BAD_REQUEST, "FAQ-006", "해당 카테고리를 사용중인 FAQ가 있습니다."),
    FAQ_CACHE_INTENT_INVALID(HttpStatus.BAD_REQUEST, "FAQ-007", "일반 안내 FAQ만 캐시 답변을 승인할 수 있습니다."),
    FAQ_CACHE_NOT_APPROVED(HttpStatus.CONFLICT, "FAQ-008", "캐시 답변이 승인된 일반 안내 FAQ만 표현을 등록할 수 있습니다."),
    FAQ_CACHE_FAQ_CHANGED(HttpStatus.CONFLICT, "FAQ-009", "등록 중 FAQ가 변경되었습니다. 최신 내용을 확인한 뒤 다시 등록해주세요."),
    FAQ_CACHE_VECTOR_NOT_READY(HttpStatus.CONFLICT, "FAQ-010", "현재 임베딩 프로필의 FAQ 질문 벡터가 준비되지 않았습니다."),
    FAQ_CACHE_QUESTION_NOT_FOUND(HttpStatus.NOT_FOUND, "FAQ-011", "해당 FAQ에 등록된 질문 표현을 찾을 수 없습니다."),
    FAQ_CACHE_QUESTION_INVALID(HttpStatus.BAD_REQUEST, "FAQ-012", "정규화 후 비어 있는 질문 표현은 등록할 수 없습니다.");

    private final HttpStatus status;
    private final String code;
    private final String message;
}
