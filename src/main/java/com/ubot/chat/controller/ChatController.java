package com.ubot.chat.controller;

import com.ubot.ai.dto.Location;
import com.ubot.auth.config.CustomUserDetails;
import com.ubot.chat.dto.request.ChatRequestDto;
import com.ubot.chat.dto.request.ChatResearchRequestDto;
import com.ubot.chat.exception.ChatErrorCode;
import com.ubot.chat.exception.ChatException;
import com.ubot.chat.job.dto.response.ChatJobStatusResponseDto;
import com.ubot.chat.job.dto.response.ChatJobSubmissionResponseDto;
import com.ubot.chat.job.service.ChatJobQueryService;
import com.ubot.chat.job.service.ChatSubmissionService.ChatSubmission;
import com.ubot.chat.service.ChatService;
import com.ubot.chat.util.ClientIpResolver;
import com.ubot.common.ApiResponse;
import com.ubot.conversation.entity.Conversation;
import com.ubot.conversation.service.ConversationService;
import com.ubot.guest.session.GuestConversationService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

// 질문·재시도·재검색은 접수만 하고 202로 jobId를 돌려줌. 답변은 GET /chat/jobs/{jobId}로 조회함
@RestController
@RequiredArgsConstructor
@RequestMapping("/chat")
public class ChatController {
	private final ChatService chatService;
	private final ChatJobQueryService chatJobQueryService;
	private final ClientIpResolver clientIpResolver;
	private final GuestConversationService guestConversationService;
	private final ConversationService conversationService;

	@PostMapping(value = "/questions", produces = MediaType.APPLICATION_JSON_VALUE)
	public ResponseEntity<ApiResponse<ChatJobSubmissionResponseDto>> createChat(
			@AuthenticationPrincipal CustomUserDetails user,
			@Valid @RequestBody ChatRequestDto request,
			HttpServletRequest httpRequest) {
		String userIp = clientIpResolver.resolve(httpRequest);
		// 내 위치 기준 재요청일 때만 좌표를 씁니다. 회원·비회원 모두 같습니다.
		Location myLocation = request.myLocation();
		if (user != null) {
			Optional<Long> claimedConversationId = guestConversationService.claimConversation(httpRequest, user.getUserId());
			UUID requestedConversationId = request.conversationId() != null || claimedConversationId.isEmpty()
					? request.conversationId()
					: conversationService.getPublicId(claimedConversationId.get());
			Conversation conversation = conversationService.getOrCreateMemberConversation(
					user.getUserId(), requestedConversationId);
			return accepted(chatService.createChat(
					user.getUserId(), conversation.getId(), request.question(), myLocation, userIp), true);
		}
		Long conversationId = guestConversationService.getOrCreateConversationId(httpRequest);
		return accepted(chatService.createGuestChat(conversationId, request.question(), myLocation, userIp), true);
	}

	@PostMapping(value = "/questions/retries", produces = MediaType.APPLICATION_JSON_VALUE)
	public ResponseEntity<ApiResponse<ChatJobSubmissionResponseDto>> retryChat(
			@AuthenticationPrincipal CustomUserDetails user,
			@RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
			HttpServletRequest httpRequest) {
		String userIp = clientIpResolver.resolve(httpRequest);
		if (user != null) {
			guestConversationService.claimConversation(httpRequest, user.getUserId());
			return accepted(chatService.retryChat(user.getUserId(), idempotencyKey, userIp), false);
		}
		Long conversationId = guestConversationService.findConversationId(httpRequest)
				.orElseThrow(() -> new ChatException(ChatErrorCode.ATTEMPT_NOT_FOUND));
		return accepted(chatService.retryGuestChat(conversationId, idempotencyKey, userIp), false);
	}

	@PostMapping(value = "/questions/research", produces = MediaType.APPLICATION_JSON_VALUE)
	public ResponseEntity<ApiResponse<ChatJobSubmissionResponseDto>> researchChat(
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
		return accepted(chatService.researchChat(
				user.getUserId(), idempotencyKey, request.intent(), request.myLocation(), userIp), false);
	}

	// 본인 작업만 조회됨(회원은 JWT, 게스트는 세션 대화)
	@GetMapping(value = "/jobs/{jobId}", produces = MediaType.APPLICATION_JSON_VALUE)
	public ApiResponse<ChatJobStatusResponseDto> getJob(
			@AuthenticationPrincipal CustomUserDetails user,
			@PathVariable Long jobId,
			HttpServletRequest httpRequest) {
		if (user != null) {
			guestConversationService.claimConversation(httpRequest, user.getUserId());
			return ApiResponse.success(chatJobQueryService.getStatus(jobId, user.getUserId(), Optional.empty()));
		}
		return ApiResponse.success(chatJobQueryService.getStatus(
				jobId, null, guestConversationService.findConversationId(httpRequest)));
	}

	private ResponseEntity<ApiResponse<ChatJobSubmissionResponseDto>> accepted(
			ChatSubmission submission, boolean includeConversationId) {
		ChatJobSubmissionResponseDto response = includeConversationId
				? submission.response().withConversationId(conversationService.getPublicId(submission.conversationId()))
				: submission.response();
		return ResponseEntity.status(HttpStatus.ACCEPTED).body(ApiResponse.success(response));
	}
}
