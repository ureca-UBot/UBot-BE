package com.ubot.billing.controller;

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
import com.ubot.billing.dto.response.BillingRecordResponseDto;
import com.ubot.billing.service.BillingService;

import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Positive;
import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/subscriptions/me/{subscriptionId}/billing-records")
@RequiredArgsConstructor
@Validated
@Tag(name = "내 월별 청구·납부 조회")
public class BillingController {
	private final BillingService billingService;

	@GetMapping
	public ApiResponse<PageResponseDto<BillingRecordResponseDto>> getMyBillingRecordList(
			@AuthenticationPrincipal CustomUserDetails userDetails,
			@PathVariable(name = "subscriptionId") @Positive Long subscriptionId,
			@RequestParam(name = "month", required = false) @DateTimeFormat(pattern = "yyyy-MM") YearMonth month,
			@RequestParam(name = "page", defaultValue = "0") @Min(0) int page,
			@RequestParam(name = "size", defaultValue = "20") @Min(1) @Max(100) int size) {
		return ApiResponse.success(billingService.getMyBillingRecordList(
				userDetails.getUserId(), subscriptionId, month, page, size));
	}
}
