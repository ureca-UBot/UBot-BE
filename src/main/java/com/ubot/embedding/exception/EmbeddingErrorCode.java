package com.ubot.embedding.exception;

import org.springframework.http.HttpStatus;

import com.ubot.common.ErrorCode;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum EmbeddingErrorCode implements ErrorCode {
    EMBEDDING_SERVICE_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "EM-001",
            "임베딩 서버 응답이 없습니다. 잠시 후 다시 시도해주세요."),
    EMBEDDING_RESPONSE_INVALID(HttpStatus.INTERNAL_SERVER_ERROR, "EM-002",
            "임베딩 서버 응답 형식이 올바르지 않습니다."),
    EMBEDDING_TIMEOUT(HttpStatus.GATEWAY_TIMEOUT, "EM-003", "임베딩 서버 응답이 지연되고 있습니다.");

    private final HttpStatus status;
    private final String code;
    private final String message;
}
