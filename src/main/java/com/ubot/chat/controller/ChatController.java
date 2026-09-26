package com.ubot.chat.controller;

import com.ubot.auth.config.CustomUserDetails;
import com.ubot.chat.dto.request.ChatRequestDto;
import com.ubot.chat.dto.response.ChatResponseDto;
import com.ubot.chat.service.ChatService;
import com.ubot.common.ApiResponse;
import jakarta.validation.Valid;
import java.util.concurrent.CompletableFuture;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.request.async.DeferredResult;

@RestController
@RequiredArgsConstructor
@RequestMapping("/chat")
public class ChatController {
	private static final long RESPONSE_TIMEOUT_MILLIS = 180_000L;

	private final ChatService chatService;

	@PostMapping(value = "/questions", produces = MediaType.APPLICATION_JSON_VALUE)
	public DeferredResult<ApiResponse<ChatResponseDto>> createChat(
			@AuthenticationPrincipal CustomUserDetails user,
			@Valid @RequestBody ChatRequestDto request
	) {
		return createResponse(chatService.createChat(user.getUserId(), request.question()));
	}

	@PostMapping(value = "/questions/retries", produces = MediaType.APPLICATION_JSON_VALUE)
	public DeferredResult<ApiResponse<ChatResponseDto>> retryChat(
			@AuthenticationPrincipal CustomUserDetails user,
			@RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey
	) {
		return createResponse(chatService.retryChat(user.getUserId(), idempotencyKey));
	}

	private DeferredResult<ApiResponse<ChatResponseDto>> createResponse(CompletableFuture<ChatResponseDto> answer) {
		DeferredResult<ApiResponse<ChatResponseDto>> response = new DeferredResult<>(RESPONSE_TIMEOUT_MILLIS);
		answer.whenComplete((result, exception) -> {
			if (exception != null) {
				response.setErrorResult(exception);
			} else {
				response.setResult(ApiResponse.success(result));
			}
		});
		return response;
	}
}
