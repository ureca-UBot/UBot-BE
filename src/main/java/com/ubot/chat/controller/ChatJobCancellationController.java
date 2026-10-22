package com.ubot.chat.controller;

import com.ubot.auth.config.CustomUserDetails;
import com.ubot.chat.job.dto.response.ChatJobCancellationResponseDto;
import com.ubot.chat.job.service.ChatJobCancellationService;
import com.ubot.common.ApiResponse;
import com.ubot.guest.session.GuestConversationService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.constraints.Positive;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/chat/jobs")
@RequiredArgsConstructor
@Validated
public class ChatJobCancellationController {

	private final ChatJobCancellationService cancellationService;
	private final GuestConversationService guestConversations;

	@PostMapping("/{jobId}/cancel")
	public ApiResponse<ChatJobCancellationResponseDto> cancel(
			@AuthenticationPrincipal CustomUserDetails user,
			@Positive @PathVariable("jobId") Long jobId,
			HttpServletRequest request) {
		if (user != null) {
			guestConversations.claimConversation(request, user.getUserId());
			return ApiResponse.success(cancellationService.cancel(jobId, user.getUserId(), Optional.empty()));
		}
		return ApiResponse.success(cancellationService.cancel(jobId, null, guestConversations.findConversationId(request)));
	}
}
