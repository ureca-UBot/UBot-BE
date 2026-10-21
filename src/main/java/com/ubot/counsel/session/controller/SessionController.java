package com.ubot.counsel.session.controller;

import com.ubot.auth.config.CustomUserDetails;
import com.ubot.common.ApiResponse;
import com.ubot.counsel.auth.model.Caller;
import com.ubot.counsel.session.dto.request.AvailabilityRequestDto;
import com.ubot.counsel.session.dto.request.HandoffRequestDto;
import com.ubot.counsel.session.dto.response.AvailabilityResponseDto;
import com.ubot.counsel.session.dto.response.HistoryMessageResponseDto;
import com.ubot.counsel.session.dto.response.SessionResponseDto;
import com.ubot.counsel.session.service.SessionService;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/counsel")
@RequiredArgsConstructor
public class SessionController {
	private final SessionService service;
	@PostMapping("/requests")
	ApiResponse<SessionResponseDto> createSession(@AuthenticationPrincipal CustomUserDetails principal,
			@Valid @RequestBody(required = false) HandoffRequestDto body) {
		return ApiResponse.success(SessionResponseDto.from(body == null ? service.createSession(Caller.from(principal))
				: service.createHandoff(Caller.from(principal), body)));
	}
	@GetMapping("/sessions/conversation/{conversationId}")
	ApiResponse<SessionResponseDto> getConversationSession(@AuthenticationPrincipal CustomUserDetails principal, @PathVariable UUID conversationId) {
		return ApiResponse.success(SessionResponseDto.from(service.getConversationSession(Caller.from(principal), conversationId)));
	}
	@GetMapping("/sessions/{id}/context")
	ApiResponse<List<HistoryMessageResponseDto>> getHistory(@AuthenticationPrincipal CustomUserDetails principal, @PathVariable UUID id,
			@RequestParam(defaultValue = "0") long afterSequence, @RequestParam(defaultValue = "100") int limit) {
		return ApiResponse.success(service.getHistory(Caller.from(principal), id, afterSequence, limit));
	}
	@GetMapping("/sessions/current")
	ApiResponse<SessionResponseDto> getCurrentSession(@AuthenticationPrincipal CustomUserDetails principal) {
		return ApiResponse.success(SessionResponseDto.from(service.getCurrentSession(Caller.from(principal))));
	}
	@GetMapping("/sessions/{id}")
	ApiResponse<SessionResponseDto> getSession(@AuthenticationPrincipal CustomUserDetails principal, @PathVariable UUID id) {
		return ApiResponse.success(SessionResponseDto.from(service.getSession(Caller.from(principal), id)));
	}
	@PostMapping("/sessions/{id}/end")
	ApiResponse<SessionResponseDto> endSession(@AuthenticationPrincipal CustomUserDetails principal, @PathVariable UUID id) {
		return ApiResponse.success(SessionResponseDto.from(service.endSession(Caller.from(principal), id)));
	}
	@PostMapping("/counselors/availability")
	ApiResponse<AvailabilityResponseDto> updateAvailability(@AuthenticationPrincipal CustomUserDetails principal, @Valid @RequestBody AvailabilityRequestDto body) {
		return ApiResponse.success(new AvailabilityResponseDto(service.updateAvailability(Caller.from(principal), body.status())));
	}
	@PostMapping("/counselors/heartbeat")
	ApiResponse<AvailabilityResponseDto> updateHeartbeat(@AuthenticationPrincipal CustomUserDetails principal) {
		return ApiResponse.success(new AvailabilityResponseDto(service.updateHeartbeat(Caller.from(principal))));
	}
}
