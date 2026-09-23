package com.ubot.chat.mvp.controller;

import com.ubot.chat.mvp.exception.ChatMvpErrorCode;
import com.ubot.common.ApiResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** Keep the shared exception handler unchanged; invalid/missing keys are request errors. */
@RestControllerAdvice(assignableTypes = ChatStreamController.class)
@Order(Ordered.HIGHEST_PRECEDENCE)
public class ChatStreamExceptionHandler {
    @ExceptionHandler(MissingRequestHeaderException.class)
    public ResponseEntity<ApiResponse<Void>> missingKey(MissingRequestHeaderException exception) {
        return ResponseEntity.badRequest().body(ApiResponse.error(ChatMvpErrorCode.INVALID_REQUEST));
    }
}
