package com.ubot.counsel.admin.controller;

import com.ubot.counsel.admin.dto.request.SafetyEvaluationRequestDto;
import com.ubot.counsel.admin.dto.response.SafetyEvaluationResponseDto;
import com.ubot.counsel.admin.dto.response.SafetyEventsResponseDto;
import com.ubot.counsel.admin.dto.response.SafetySummaryResponseDto;
import com.ubot.counsel.admin.service.AdminCounselService;
import com.ubot.counsel.auth.model.Caller;
import com.ubot.common.ApiResponse;
import jakarta.validation.Valid;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import com.ubot.auth.config.CustomUserDetails;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/counsel/admin")
public class AdminCounselController {
	private final AdminCounselService service;
	public AdminCounselController(AdminCounselService service) {
		this.service = service;
	}
	@GetMapping("/safety/events")
	ApiResponse<SafetyEventsResponseDto> getSafetyEvents(@AuthenticationPrincipal CustomUserDetails principal, @RequestParam(defaultValue="") String decision,
			@RequestParam(required=false) Instant from, @RequestParam(required=false) Instant to,
			@RequestParam(defaultValue="0") int page) {
		Instant end = to == null ? Instant.now() : to;
		return ApiResponse.success(service.getSafetyEvents(Caller.from(principal), decision, from == null ? end.minus(1, ChronoUnit.DAYS) : from, end, page));
	}
	@GetMapping("/safety/summary")
	ApiResponse<SafetySummaryResponseDto> getSafetySummary(@AuthenticationPrincipal CustomUserDetails principal, @RequestParam(required=false) Instant from,
			@RequestParam(required=false) Instant to) {
		Instant end = to == null ? Instant.now() : to;
		return ApiResponse.success(service.getSafetySummary(Caller.from(principal), from == null ? end.minus(1, ChronoUnit.DAYS) : from, end));
	}
	@PostMapping("/counselors/{userId}")
	ApiResponse<Void> promote(@AuthenticationPrincipal CustomUserDetails principal, @PathVariable long userId) {
		service.updateCounselorRole(Caller.from(principal), userId, true); return ApiResponse.success(null);
	}
	@DeleteMapping("/counselors/{userId}")
	ApiResponse<Void> demote(@AuthenticationPrincipal CustomUserDetails principal, @PathVariable long userId) {
		service.updateCounselorRole(Caller.from(principal), userId, false); return ApiResponse.success(null);
	}
	@PostMapping("/safety/evaluate")
	ApiResponse<SafetyEvaluationResponseDto> evaluateMessage(@AuthenticationPrincipal CustomUserDetails principal, @Valid @RequestBody SafetyEvaluationRequestDto body) {
		return ApiResponse.success(SafetyEvaluationResponseDto.from(service.evaluateMessage(Caller.from(principal), body.content())));
	}
}
