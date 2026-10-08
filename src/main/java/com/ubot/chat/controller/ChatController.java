package com.ubot.chat.controller;

import com.ubot.ai.dto.Location;
import com.ubot.auth.config.CustomUserDetails;
import com.ubot.chat.dto.request.ChatRequestDto;
import com.ubot.chat.dto.request.ChatResearchRequestDto;
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
			HttpServletRequest httpRequest) {
		String userIp = clientIpResolver.resolve(httpRequest);
		// 내 위치 기준 재요청일 때만 좌표를 씁니다. 회원·비회원 모두 같습니다.
		Location myLocation = request.myLocation();
		if (user != null) {
			guestConversationService.claimConversation(httpRequest, user.getUserId());
			return createResponse(chatService.createChat(user.getUserId(), request.question(), myLocation, userIp));
		}
		Long conversationId = guestConversationService.getOrCreateConversationId(httpRequest);
		return createResponse(chatService.createGuestChat(conversationId, request.question(), myLocation, userIp));
	}

	@PostMapping(value = "/questions/retries", produces = MediaType.APPLICATION_JSON_VALUE)
	public DeferredResult<ApiResponse<ChatResponseDto>> retryChat(
			@AuthenticationPrincipal CustomUserDetails user,
			@RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
			HttpServletRequest httpRequest) {
		String userIp = clientIpResolver.resolve(httpRequest);
		if (user != null) {
			guestConversationService.claimConversation(httpRequest, user.getUserId());
			return createResponse(chatService.retryChat(user.getUserId(), idempotencyKey, userIp));
		}
		Long conversationId = guestConversationService.findConversationId(httpRequest)
				.orElseThrow(() -> new ChatException(ChatErrorCode.ATTEMPT_NOT_FOUND));
		return createResponse(chatService.retryGuestChat(conversationId, idempotencyKey, userIp));
	}

	@PostMapping(value = "/questions/research", produces = MediaType.APPLICATION_JSON_VALUE)
	public DeferredResult<ApiResponse<ChatResponseDto>> researchChat(
			@AuthenticationPrincipal CustomUserDetails user,
			@RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
			@Valid @RequestBody ChatResearchRequestDto request,
			HttpServletRequest httpRequest) {
		// 재검색은 회원 전용입니다. 비로그인 요청은 보통 인증 단계에서 먼저 거절되고, 이 검사는 방어용입니다.
		if (user == null) {
			throw new ChatException(ChatErrorCode.LOGIN_REQUIRED);
		}
		String userIp = clientIpResolver.resolve(httpRequest);
		// 게스트로 질문한 뒤 로그인한 경우를 위해, 재시도와 같이 대화를 먼저 회원에게 귀속시킵니다.
		guestConversationService.claimConversation(httpRequest, user.getUserId());
		return createResponse(chatService.researchChat(
				user.getUserId(), idempotencyKey, request.intent(), request.myLocation(), userIp));
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
