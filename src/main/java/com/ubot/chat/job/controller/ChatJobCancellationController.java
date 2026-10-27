package com.ubot.chat.job.controller;

import com.ubot.auth.config.CustomUserDetails;
import com.ubot.chat.job.dto.response.ChatJobCancellationResponseDto;
import com.ubot.chat.job.service.ChatJobCancellationService;
import com.ubot.common.ApiResponse;
import com.ubot.guest.session.GuestConversationService;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/chat/jobs")
public class ChatJobCancellationController {

	private final ChatJobCancellationService cancellationService;
	private final GuestConversationService guestConversationService;

	@PostMapping("/{jobId}/cancel")
	public ApiResponse<ChatJobCancellationResponseDto> cancel(
			@AuthenticationPrincipal CustomUserDetails user, @PathVariable Long jobId,
			HttpServletRequest request) {
		if (user != null) {
			guestConversationService.claimConversation(request, user.getUserId());
			return ApiResponse.success(cancellationService.cancel(jobId, user.getUserId(), Optional.empty()));
		}
		return ApiResponse.success(cancellationService.cancel(
				jobId, null, guestConversationService.findConversationId(request)));
	}
}
