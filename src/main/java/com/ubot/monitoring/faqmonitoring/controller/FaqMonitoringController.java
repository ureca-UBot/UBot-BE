package com.ubot.monitoring.faqmonitoring.controller;

import com.ubot.auth.config.CustomUserDetails;
import com.ubot.common.ApiResponse;
import com.ubot.common.PageResponseDto;
import com.ubot.monitoring.faqmonitoring.dto.model.detail.FaqUsageTrendPeriod;
import com.ubot.monitoring.faqmonitoring.dto.model.improvelayer.FaqDuplicateCandidate;
import com.ubot.monitoring.faqmonitoring.dto.model.improvelayer.FaqImprovementType;
import com.ubot.monitoring.faqmonitoring.dto.request.FaqImprovementExclusionRequest;
import com.ubot.monitoring.faqmonitoring.dto.response.FaqDetailResponse;
import com.ubot.monitoring.faqmonitoring.dto.response.FaqImprovementResponse;
import com.ubot.monitoring.faqmonitoring.dto.response.FaqImprovementExclusionResponse;
import com.ubot.monitoring.faqmonitoring.dto.response.FaqImprovementExclusionHistoryResponse;
import com.ubot.monitoring.faqmonitoring.dto.response.FaqMonitoringResponse;
import com.ubot.monitoring.faqmonitoring.service.FaqMonitoringService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Positive;
import java.time.LocalDateTime;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@Validated
@RequestMapping("/admin/faq-monitoring")
public class FaqMonitoringController {
	private final FaqMonitoringService faqMonitoringService;

	@GetMapping
	public ApiResponse<FaqMonitoringResponse> getMonitoringData(
			@RequestParam("startAt") @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime startAt,
			@RequestParam("endAt") @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime endAt
	) {
		return ApiResponse.success(faqMonitoringService.getMonitoringData(startAt, endAt));
	}

	@GetMapping("/faqs/{faqId}")
	public ApiResponse<FaqDetailResponse> getFaqDetail(
			@RequestParam(name = "period", defaultValue = "MONTH") FaqUsageTrendPeriod period,
			@RequestParam(name = "asOf", required = false)
			@DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime asOf,
			@PathVariable("faqId") Long faqId
	) {
		return ApiResponse.success(faqMonitoringService.getFaqDetail(faqId, period, asOf));
	}

	@GetMapping("/improvements")
	public ApiResponse<FaqImprovementResponse> getFaqImprovements(
			@RequestParam(name = "types", required = false) List<FaqImprovementType> types,
			@RequestParam(defaultValue = "0") @Min(0) int page,
			@RequestParam(defaultValue = "10") @Min(1) @Max(100) int size
	) {
		return ApiResponse.success(faqMonitoringService.getFaqImprovements(types, page, size));
	}

	@GetMapping("/improvement-exclusions")
	public ApiResponse<PageResponseDto<FaqImprovementExclusionResponse>> getFaqImprovementExclusions(
			@RequestParam(defaultValue = "0") @Min(0) int page,
			@RequestParam(defaultValue = "10") @Min(1) @Max(100) int size
	) {
		return ApiResponse.success(faqMonitoringService.getFaqImprovementExclusions(page, size));
	}

	@GetMapping("/improvement-exclusions/history")
	public ApiResponse<PageResponseDto<FaqImprovementExclusionHistoryResponse>> getFaqImprovementExclusionHistory(
			@RequestParam(defaultValue = "0") @Min(0) int page,
			@RequestParam(defaultValue = "10") @Min(1) @Max(100) int size
	) {
		return ApiResponse.success(faqMonitoringService.getFaqImprovementExclusionHistory(page, size));
	}

	@DeleteMapping("/improvement-exclusions/{exclusionId}")
	public ApiResponse<Void> deleteFaqImprovementExclusion(
			@PathVariable("exclusionId") @Positive Long exclusionId
	) {
		faqMonitoringService.deleteFaqImprovementExclusion(exclusionId);
		return ApiResponse.success(null);
	}

	@GetMapping("/improvements/{faqId}/duplicates")
	public ApiResponse<List<FaqDuplicateCandidate>> getDuplicateCandidates(
			@PathVariable("faqId") @Positive Long faqId
	) {
		return ApiResponse.success(faqMonitoringService.getDuplicateCandidates(faqId));
	}

	@PostMapping("/improvements/{faqId}/exclusions")
	public ApiResponse<Void> excludeFaqImprovement(
			@PathVariable("faqId") @Positive Long faqId,
			@Valid @RequestBody FaqImprovementExclusionRequest request,
			@AuthenticationPrincipal CustomUserDetails administrator
	) {
		faqMonitoringService.excludeFaqImprovement(faqId, request, administrator.getUserId());
		return ApiResponse.success(null);
	}

	@PatchMapping("/improvement-exclusions/{exclusionId}/release")
	public ApiResponse<Void> releaseFaqImprovementExclusion(
			@PathVariable("exclusionId") @Positive Long exclusionId,
			@AuthenticationPrincipal CustomUserDetails administrator
	) {
		faqMonitoringService.releaseFaqImprovementExclusion(exclusionId, administrator.getUserId());
		return ApiResponse.success(null);
	}
}
