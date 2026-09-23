package com.ubot.chat.controller;

import com.ubot.auth.config.CustomUserDetails;
import com.ubot.chat.dto.request.ChatRequestDto;
import com.ubot.chat.exception.ChatErrorCode;
import com.ubot.chat.exception.ChatException;
import com.ubot.chat.service.ChatService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RestController
@RequiredArgsConstructor
@RequestMapping("/chat")
public class ChatController {
	private final ChatService chatService;

	@PostMapping(value = "/questions", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
	public ResponseEntity<SseEmitter> createChat(
			@AuthenticationPrincipal CustomUserDetails user,
			@Valid @RequestBody ChatRequestDto request
	) {
		return createStreamResponse(chatService.createChat(getUserId(user), request.question()));
	}

	@PostMapping(value = "/questions/retries", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
	public ResponseEntity<SseEmitter> retryChat(
			@AuthenticationPrincipal CustomUserDetails user,
			@RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey
	) {
		return createStreamResponse(chatService.retryChat(getUserId(user), idempotencyKey));
	}

	private Long getUserId(CustomUserDetails user) {
		if (user == null) {
			throw new ChatException(ChatErrorCode.LOGIN_REQUIRED);
		}
		return user.getUserId();
	}

	private ResponseEntity<SseEmitter> createStreamResponse(SseEmitter emitter) {
		return ResponseEntity.ok()
				.header(HttpHeaders.CACHE_CONTROL, "no-cache")
				.header("X-Accel-Buffering", "no")
				.body(emitter);
	}
}
