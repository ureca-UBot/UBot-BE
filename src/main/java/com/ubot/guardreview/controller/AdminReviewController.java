package com.ubot.guardreview.controller;

import com.ubot.guardreview.dto.response.ReviewCheckResponseDto;
import com.ubot.guardreview.dto.response.ReviewCheckDetailResponseDto;

import com.ubot.auth.config.CustomUserDetails;
import com.ubot.common.ApiResponse;
import com.ubot.guardreview.model.ReviewModels.Kind;
import com.ubot.guardreview.model.ReviewModels.Candidate;
import com.ubot.common.PageResponseDto;
import com.ubot.guardreview.repository.ReviewRepository;
import com.ubot.guardreview.service.ReviewService;
import com.ubot.guardreview.service.ReviewCheckService;
import com.ubot.guardreview.model.ReviewCheckModels.Origin;
import com.ubot.guardreview.model.ReviewCheckModels.Outcome;
import com.ubot.notification.repository.NotificationRepository;
import com.ubot.notification.service.NotificationService;
import java.util.List;
import java.util.Map;
import com.ubot.guardreview.dto.response.ReviewRunResponseDto;
import com.ubot.guardreview.model.ReviewModels.CaseResult;
import com.ubot.guardreview.model.ReviewModels.Settings;
import com.ubot.notification.dto.response.NotificationResponseDto;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import jakarta.validation.Valid;
import com.ubot.guardreview.dto.request.ReviewSettingsRequestDto;
import com.ubot.guardreview.dto.request.ReviewCandidateUpdateRequestDto;
import com.ubot.guardreview.dto.request.ReviewValidationRequestDto;
import com.ubot.guardreview.dto.request.ReviewApprovalRequestDto;
import com.ubot.guardreview.dto.request.ReviewDecisionRequestDto;

@RestController
@RequestMapping("/admin/guard-review")
@RequiredArgsConstructor
public class AdminReviewController {
	private final ReviewService service;
	private final ReviewRepository repository;
	private final NotificationRepository notifications;
	private final NotificationService notificationService;
	private final ReviewCheckService checks;
	private long admin(CustomUserDetails user) {
		if (user == null || user.getAuthorities().stream().noneMatch(a -> "ROLE_ADMIN".equals(a.getAuthority())))
			throw new org.springframework.security.access.AccessDeniedException("관리자 권한이 필요합니다.");
		return user.getUserId();
	}
	@GetMapping
	public ApiResponse<Map<String, Object>> getOverview(@AuthenticationPrincipal CustomUserDetails user) {
		admin(user);
		return ApiResponse.success(service.getOverview());
	}
	@GetMapping("/candidates")
	public ApiResponse<PageResponseDto<Candidate>> getCandidatePage(@AuthenticationPrincipal CustomUserDetails user,
			@RequestParam Kind kind, @RequestParam(defaultValue = "0") int page,
			@RequestParam(defaultValue = "20") int size,
			@RequestParam(required = false) Origin origin, @RequestParam(required = false) Outcome outcome) {
		admin(user);
		return ApiResponse.success(service.getCandidatePage(kind, page, size, origin, outcome));
	}
	@GetMapping("/checks")
	public ApiResponse<PageResponseDto<ReviewCheckResponseDto>> getChecks(
			@AuthenticationPrincipal CustomUserDetails user,
			@RequestParam(required = false) Origin origin, @RequestParam(required = false) Outcome outcome,
			@RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
		admin(user);
		return ApiResponse.success(checks.page(origin, outcome, page, size));
	}
	@GetMapping("/checks/{id}")
	public ApiResponse<ReviewCheckDetailResponseDto> getCheck(
			@AuthenticationPrincipal CustomUserDetails user, @PathVariable long id) {
		admin(user);
		return ApiResponse.success(checks.detail(id));
	}
	@GetMapping("/runs/{id}")
	public ApiResponse<ReviewRunResponseDto> getRun(@AuthenticationPrincipal CustomUserDetails user,
			@PathVariable long id) {
		admin(user);
		return ApiResponse.success(service.getRun(id));
	}
	@GetMapping("/runs/{id}/cases")
	public ApiResponse<PageResponseDto<CaseResult>> getValidationCasePage(
			@AuthenticationPrincipal CustomUserDetails user, @PathVariable long id,
			@RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
		admin(user);
		return ApiResponse.success(service.getValidationCasePage(id, page, size));
	}
	@PatchMapping("/settings")
	public ApiResponse<Settings> updateSettings(@AuthenticationPrincipal CustomUserDetails user,
			@Valid @RequestBody ReviewSettingsRequestDto body) {
		return ApiResponse.success(service.updateSettings(body.enabled(), body.intervalMinutes(),
				body.groupingThreshold(), body.revision(), admin(user)));
	}
	@PostMapping("/analysis")
	public ApiResponse<Long> startAnalysis(@AuthenticationPrincipal CustomUserDetails user) {
		return ApiResponse.success(service.startAnalysis(admin(user)));
	}
	@PostMapping("/runs/{id}/resume")
	public ApiResponse<Void> resumeAnalysis(@AuthenticationPrincipal CustomUserDetails user, @PathVariable long id) {
		admin(user);
		service.resumeAnalysis(id);
		return ApiResponse.success(null);
	}
	@PostMapping("/reassessment")
	public ApiResponse<Long> startReassessment(@AuthenticationPrincipal CustomUserDetails user,
			@Valid @RequestBody com.ubot.guardreview.dto.request.ReviewReassessmentRequestDto body) {
		return ApiResponse.success(service.startReassessment(body.origin(), body.checkIds(), admin(user)));
	}
	@PostMapping("/validation")
	public ApiResponse<Long> startValidation(@AuthenticationPrincipal CustomUserDetails user,
			@Valid @RequestBody ReviewValidationRequestDto body) {
		return ApiResponse.success(service.startValidation(body.selection(), admin(user)));
	}
	@PatchMapping("/candidates/{id}")
	public ApiResponse<Candidate> updateCandidate(@AuthenticationPrincipal CustomUserDetails user,
			@PathVariable long id, @Valid @RequestBody ReviewCandidateUpdateRequestDto body) {
		return ApiResponse
				.success(service.updateCandidate(id, body.revision(), body.expression(), body.reason(), admin(user)));
	}
	@PostMapping("/approval")
	public ApiResponse<Void> approveCandidates(@AuthenticationPrincipal CustomUserDetails user,
			@Valid @RequestBody ReviewApprovalRequestDto body) {
		service.approveCandidates(body.runId(), body.selection(), admin(user));
		return ApiResponse.success(null);
	}
	@PostMapping("/candidates/{id}/decision")
	public ApiResponse<Void> updateCandidateDecision(@AuthenticationPrincipal CustomUserDetails user,
			@PathVariable long id, @Valid @RequestBody ReviewDecisionRequestDto body) {
		service.updateCandidateDecision(id, body.revision(), body.action(), admin(user));
		return ApiResponse.success(null);
	}
	@GetMapping("/candidates/{id}/findings")
	public ApiResponse<List<Map<String, Object>>> getFindings(@AuthenticationPrincipal CustomUserDetails user,
			@PathVariable long id,
			@RequestParam(required = false) Origin origin, @RequestParam(required = false) Outcome outcome) {
		admin(user);
		return ApiResponse.success(repository.findings(id, origin, outcome));
	}
	@GetMapping("/notifications")
	public ApiResponse<List<Map<String, Object>>> getNotifications(@AuthenticationPrincipal CustomUserDetails user) {
		return ApiResponse.success(notifications
				.findTop50ByUserIdAndTypeOrderByCreatedAtDescNotificationIdDesc(admin(user),
						com.ubot.notification.enums.NotificationType.GUARD_REVIEW)
				.stream()
				.map(n -> Map.<String, Object>of("id", n.getNotificationId(), "candidateId",
						n.getGuardReviewCandidateId(), "message", n.getMessage(), "read", n.getReadAt() != null))
				.toList());
	}
	@PatchMapping("/notifications/{id}/read")
	public ApiResponse<NotificationResponseDto> readNotification(@AuthenticationPrincipal CustomUserDetails user,
			@PathVariable long id) {
		return ApiResponse.success(notificationService.readNotification(admin(user), id));
	}
}
