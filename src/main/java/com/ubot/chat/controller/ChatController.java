package com.ubot.chat.controller;

import com.ubot.auth.config.CustomUserDetails;
import com.ubot.chat.dto.request.ChatRequestDto;
import com.ubot.chat.dto.response.ChatResponseDto;
import com.ubot.chat.exception.ChatErrorCode;
import com.ubot.chat.exception.ChatException;
import com.ubot.chat.service.ChatAnswerTask;
import com.ubot.chat.service.ChatService;
import com.ubot.chat.util.ClientIpResolver;
import com.ubot.common.ApiResponse;
import com.ubot.guest.session.GuestConversationService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
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
	@Value("${CHAT_RESPONSE_TIMEOUT_MILLIS:180000}")
	private long responseTimeoutMillis;

	private final ChatService chatService;
	private final ClientIpResolver clientIpResolver;
	private final GuestConversationService guestConversationService;

	@PostMapping(value = "/questions", produces = MediaType.APPLICATION_JSON_VALUE)
	public DeferredResult<ApiResponse<ChatResponseDto>> createChat(
			@AuthenticationPrincipal CustomUserDetails user,
			@Valid @RequestBody ChatRequestDto request,
			HttpServletRequest httpRequest
	) {
		String userIp = clientIpResolver.resolve(httpRequest);
		if (user != null) {
			return createResponse(chatService.createChat(user.getUserId(), request.question(), userIp));
		}
		Long conversationId = guestConversationService.getOrCreateConversationId(httpRequest);
		return createResponse(chatService.createGuestChat(conversationId, request.question(), userIp));
	}

	@PostMapping(value = "/questions/retries", produces = MediaType.APPLICATION_JSON_VALUE)
	public DeferredResult<ApiResponse<ChatResponseDto>> retryChat(
			@AuthenticationPrincipal CustomUserDetails user,
			@RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
			HttpServletRequest httpRequest
	) {
		String userIp = clientIpResolver.resolve(httpRequest);
		if (user != null) {
			return createResponse(chatService.retryChat(user.getUserId(), idempotencyKey, userIp));
		}
		Long conversationId = guestConversationService.findConversationId(httpRequest)
				.orElseThrow(() -> new ChatException(ChatErrorCode.ATTEMPT_NOT_FOUND));
		return createResponse(chatService.retryGuestChat(conversationId, idempotencyKey, userIp));
	}

	private DeferredResult<ApiResponse<ChatResponseDto>> createResponse(ChatAnswerTask answer) {
		DeferredResult<ApiResponse<ChatResponseDto>> response = new DeferredResult<>(responseTimeoutMillis);
		response.onTimeout(answer::timeout);
		answer.result().whenComplete((result, exception) -> {
			if (exception != null) {
				response.setErrorResult(exception);
			} else {
				response.setResult(ApiResponse.success(result));
			}
		});
		return response;
	}
}
