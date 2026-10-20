package com.ubot.counsel.history.controller;

import com.ubot.auth.config.CustomUserDetails;
import com.ubot.common.ApiResponse;
import com.ubot.counsel.auth.model.Caller;
import com.ubot.counsel.history.dto.response.ConversationTimelineResponseDto;
import com.ubot.counsel.history.service.ConversationTimelineService;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/conversations")
@RequiredArgsConstructor
public class ConversationTimelineController {
	private final ConversationTimelineService conversationTimelineService;

	@GetMapping("/{conversationId}/timeline")
	public ApiResponse<List<ConversationTimelineResponseDto>> getTimeline(
			@PathVariable UUID conversationId,
			@RequestParam(defaultValue = "0") long afterSequence,
			@RequestParam(defaultValue = "100") int limit,
			@AuthenticationPrincipal CustomUserDetails principal
	) {
		return ApiResponse.success(conversationTimelineService.getTimeline(
				Caller.from(principal), conversationId, afterSequence, limit));
	}
}
