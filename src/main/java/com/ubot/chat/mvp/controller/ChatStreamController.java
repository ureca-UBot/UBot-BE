package com.ubot.chat.mvp.controller;

import com.ubot.auth.config.CustomUserDetails;
import com.ubot.chat.mvp.dto.ChatAttemptResponse;
import com.ubot.chat.mvp.dto.ChatStreamRequest;
import com.ubot.chat.mvp.dto.ChatSessionResponse;
import com.ubot.chat.mvp.exception.ChatMvpErrorCode;
import com.ubot.chat.mvp.exception.ChatMvpException;
import com.ubot.chat.mvp.service.ChatStreamService;
import com.ubot.common.ApiResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/** Session routes from the team's API specification. The legacy controller stays unchanged. */
@RestController
@RequestMapping("/chat/sessions")
@RequiredArgsConstructor
public class ChatStreamController {
    private final ChatStreamService service;

    @PostMapping
    public ResponseEntity<ApiResponse<ChatSessionResponse>> createSession(
            @AuthenticationPrincipal CustomUserDetails principal) {
        return ResponseEntity.status(201).body(ApiResponse.success(service.createSession(userId(principal))));
    }

    @PostMapping(value = "/{sessionId}/questions", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public ResponseEntity<SseEmitter> create(
            @AuthenticationPrincipal CustomUserDetails principal,
            @PathVariable long sessionId,
            @Valid @RequestBody ChatStreamRequest request) {
        return stream(service.start(userId(principal), sessionId, request.question()));
    }

    @PostMapping(value = "/{sessionId}/questions/{questionId}/retries", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public ResponseEntity<SseEmitter> retry(
            @AuthenticationPrincipal CustomUserDetails principal,
            @PathVariable long sessionId,
            @PathVariable long questionId,
            @RequestHeader("Idempotency-Key") String key) {
        return stream(service.retry(userId(principal), sessionId, questionId, key));
    }

    @GetMapping("/{sessionId}/questions/{questionId}")
    public ApiResponse<ChatAttemptResponse> result(
            @AuthenticationPrincipal CustomUserDetails principal,
            @PathVariable long sessionId, @PathVariable long questionId) {
        return ApiResponse.success(service.findQuestion(userId(principal), sessionId, questionId));
    }

    private long userId(CustomUserDetails principal) {
        if (principal == null || principal.getUserId() == null) {
            throw new ChatMvpException(ChatMvpErrorCode.LOGIN_REQUIRED);
        }
        return principal.getUserId();
    }

    private ResponseEntity<SseEmitter> stream(SseEmitter emitter) {
        return ResponseEntity.ok().contentType(MediaType.TEXT_EVENT_STREAM)
                .header("Cache-Control", "no-cache, no-transform")
                .header("X-Accel-Buffering", "no").body(emitter);
    }
}
