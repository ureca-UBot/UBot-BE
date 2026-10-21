package com.ubot.conversation.controller;

import com.ubot.auth.config.CustomUserDetails;
import com.ubot.common.ApiResponse;
import com.ubot.conversation.dto.request.FaqSelectionRequestDto;
import com.ubot.conversation.dto.response.FaqSelectionResponseDto;
import com.ubot.conversation.service.ConversationFaqSelectionService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/conversations/faq-selections")
@RequiredArgsConstructor
public class ConversationFaqSelectionController {
	private final ConversationFaqSelectionService service;

	@PostMapping
	public ApiResponse<FaqSelectionResponseDto> save(@AuthenticationPrincipal CustomUserDetails principal,
			@Valid @RequestBody FaqSelectionRequestDto request) {
		return ApiResponse.success(service.save(principal.getUserId(), request));
	}
}
