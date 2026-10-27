package com.ubot.usage.controller;

import java.time.YearMonth;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.ubot.auth.config.CustomUserDetails;
import com.ubot.common.ApiResponse;
import com.ubot.common.PageResponseDto;
import com.ubot.usage.dto.response.UsageRecordResponseDto;
import com.ubot.usage.service.UsageService;

import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Positive;
import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/subscriptions/me/{subscriptionId}/usage-records")
@RequiredArgsConstructor
@Validated
@Tag(name = "내 월별 사용량 조회")
public class UsageController {
	private final UsageService usageService;

	@GetMapping
	public ApiResponse<PageResponseDto<UsageRecordResponseDto>> getMyUsageRecordList(
			@AuthenticationPrincipal CustomUserDetails userDetails,
			@PathVariable(name = "subscriptionId") @Positive Long subscriptionId,
			@RequestParam(name = "month", required = false) @DateTimeFormat(pattern = "yyyy-MM") YearMonth month,
			@RequestParam(name = "page", defaultValue = "0") @Min(0) int page,
			@RequestParam(name = "size", defaultValue = "20") @Min(1) @Max(100) int size) {
		return ApiResponse.success(usageService.getMyUsageRecordList(
				userDetails.getUserId(), subscriptionId, month, page, size));
	}
}
