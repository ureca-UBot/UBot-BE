package com.ubot.conversation.controller;

import com.ubot.auth.config.CustomUserDetails;
import com.ubot.common.ApiResponse;
import com.ubot.common.PageResponseDto;
import com.ubot.conversation.dto.response.ConversationMessageResponseDto;
import com.ubot.conversation.dto.response.ConversationResponseDto;
import com.ubot.conversation.service.ConversationService;
import jakarta.validation.constraints.Min;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/conversations")
@RequiredArgsConstructor
@Validated
public class ConversationController {
	private final ConversationService conversationService;

	@GetMapping("/me")
	public ApiResponse<PageResponseDto<ConversationResponseDto>> getMyConversationList(
			@RequestParam(name = "page", defaultValue = "0") @Min(0) int page,
			@RequestParam(name = "size", defaultValue = "20") int size,
			@AuthenticationPrincipal CustomUserDetails userDetails
	){
		return ApiResponse.success(conversationService.getMyConversationList(userDetails.getUserId(), page, size));
	}

	@GetMapping("/{conversationId}/messages")
	public ApiResponse<List<ConversationMessageResponseDto>> getConversationMessageList(
			@PathVariable("conversationId") UUID conversationId,
			@AuthenticationPrincipal CustomUserDetails userDetails
	){
		return ApiResponse.success(conversationService.getConversationMessageList(userDetails.getUserId(), conversationId));
	}

	@DeleteMapping("/{conversationId}")
	public ResponseEntity<Void> deleteConversation(
			@PathVariable("conversationId") UUID conversationId,
			@AuthenticationPrincipal CustomUserDetails userDetails
	) {
		conversationService.deleteConversation(userDetails.getUserId(), conversationId);
		return ResponseEntity.noContent().build();
	}
}
