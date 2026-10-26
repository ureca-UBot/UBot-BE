package com.ubot.subscription.controller;

import java.util.List;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.ubot.auth.config.CustomUserDetails;
import com.ubot.common.ApiResponse;
import com.ubot.plan.dto.response.PlanDetailResponseDto;
import com.ubot.subscription.dto.response.SubscriptionAddonResponseDto;
import com.ubot.subscription.dto.response.PhoneNumberAvailabilityResponseDto;
import com.ubot.subscription.dto.response.SubscriptionBundleResponseDto;
import com.ubot.subscription.dto.response.SubscriptionResponseDto;
import com.ubot.subscription.dto.response.SubscriptionRoamingResponseDto;
import com.ubot.subscription.service.SubscriptionService;

import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Pattern;
import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/subscriptions/me")
@RequiredArgsConstructor
@Validated
@Tag(name = "내 회선·가입 상품 조회")
public class SubscriptionController {
	private final SubscriptionService subscriptionService;

	@GetMapping("/phone-number-availability")
	public ApiResponse<PhoneNumberAvailabilityResponseDto> checkPhoneNumber(
			@RequestParam(name = "phoneNumber") @Pattern(regexp = "^010-?[0-9]{4}-?[0-9]{4}$") String phoneNumber) {
		return ApiResponse.success(subscriptionService.checkPhoneNumber(phoneNumber));
	}

	@GetMapping
	public ApiResponse<List<SubscriptionResponseDto>> getMySubscriptionList(
			@AuthenticationPrincipal CustomUserDetails userDetails) {
		return ApiResponse.success(subscriptionService.getMySubscriptionList(userDetails.getUserId()));
	}

	@GetMapping("/{subscriptionId}")
	public ApiResponse<SubscriptionResponseDto> getMySubscription(
			@AuthenticationPrincipal CustomUserDetails userDetails,
			@PathVariable(name = "subscriptionId") @Positive Long subscriptionId) {
		return ApiResponse.success(subscriptionService.getMySubscription(userDetails.getUserId(), subscriptionId));
	}

	@GetMapping("/{subscriptionId}/plan")
	public ApiResponse<PlanDetailResponseDto> getMyPlan(
			@AuthenticationPrincipal CustomUserDetails userDetails,
			@PathVariable(name = "subscriptionId") @Positive Long subscriptionId) {
		return ApiResponse.success(subscriptionService.getMyPlan(userDetails.getUserId(), subscriptionId));
	}

	@GetMapping("/{subscriptionId}/bundles")
	public ApiResponse<List<SubscriptionBundleResponseDto>> getMyBundleList(
			@AuthenticationPrincipal CustomUserDetails userDetails,
			@PathVariable(name = "subscriptionId") @Positive Long subscriptionId) {
		return ApiResponse.success(subscriptionService.getMyBundleList(userDetails.getUserId(), subscriptionId));
	}

	@GetMapping("/{subscriptionId}/addons")
	public ApiResponse<List<SubscriptionAddonResponseDto>> getMyAddonList(
			@AuthenticationPrincipal CustomUserDetails userDetails,
			@PathVariable(name = "subscriptionId") @Positive Long subscriptionId) {
		return ApiResponse.success(subscriptionService.getMyAddonList(userDetails.getUserId(), subscriptionId));
	}

	@GetMapping("/{subscriptionId}/roamings")
	public ApiResponse<List<SubscriptionRoamingResponseDto>> getMyRoamingList(
			@AuthenticationPrincipal CustomUserDetails userDetails,
			@PathVariable(name = "subscriptionId") @Positive Long subscriptionId) {
		return ApiResponse.success(subscriptionService.getMyRoamingList(userDetails.getUserId(), subscriptionId));
	}
}
