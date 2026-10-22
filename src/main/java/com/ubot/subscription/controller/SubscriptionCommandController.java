package com.ubot.subscription.controller;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import com.ubot.auth.config.CustomUserDetails;
import com.ubot.common.ApiResponse;
import com.ubot.plan.dto.response.PlanDetailResponseDto;
import com.ubot.subscription.dto.request.SubscriptionRequestDtos.AddonSubscribeRequestDto;
import com.ubot.subscription.dto.request.SubscriptionRequestDtos.BundleJoinRequestDto;
import com.ubot.subscription.dto.request.SubscriptionRequestDtos.PlanChangeRequestDto;
import com.ubot.subscription.dto.request.SubscriptionRequestDtos.RoamingScheduleRequestDto;
import com.ubot.subscription.dto.response.BundleGroupResponseDto;
import com.ubot.subscription.dto.response.SubscriptionAddonResponseDto;
import com.ubot.subscription.dto.response.SubscriptionRoamingResponseDto;
import com.ubot.subscription.service.BundleGroupService;
import com.ubot.subscription.service.SubscriptionCommandService;

import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/subscriptions/me/{subscriptionId}")
@RequiredArgsConstructor
@Validated
@Tag(name = "내 회선 상품 가입·변경")
public class SubscriptionCommandController {

	private final SubscriptionCommandService commandService;
	private final BundleGroupService bundleGroupService;

	@PatchMapping("/plan")
	public ApiResponse<PlanDetailResponseDto> changePlan(
			@AuthenticationPrincipal CustomUserDetails userDetails,
			@PathVariable(name = "subscriptionId") @Positive Long subscriptionId,
			@Valid @RequestBody PlanChangeRequestDto request) {
		return ApiResponse.success(commandService.changePlan(userDetails.getUserId(), subscriptionId, request.planId()));
	}

	@PostMapping("/addons")
	@ResponseStatus(HttpStatus.CREATED)
	public ApiResponse<SubscriptionAddonResponseDto> subscribeAddon(
			@AuthenticationPrincipal CustomUserDetails userDetails,
			@PathVariable(name = "subscriptionId") @Positive Long subscriptionId,
			@Valid @RequestBody AddonSubscribeRequestDto request) {
		return ApiResponse.success(
				commandService.subscribeAddon(userDetails.getUserId(), subscriptionId, request.addonServiceId()));
	}

	@DeleteMapping("/addons/{subscriptionAddonId}")
	public ApiResponse<SubscriptionAddonResponseDto> cancelAddon(
			@AuthenticationPrincipal CustomUserDetails userDetails,
			@PathVariable(name = "subscriptionId") @Positive Long subscriptionId,
			@PathVariable(name = "subscriptionAddonId") @Positive Long subscriptionAddonId) {
		return ApiResponse.success(
				commandService.cancelAddon(userDetails.getUserId(), subscriptionId, subscriptionAddonId));
	}

	@PostMapping("/roamings")
	@ResponseStatus(HttpStatus.CREATED)
	public ApiResponse<SubscriptionRoamingResponseDto> scheduleRoaming(
			@AuthenticationPrincipal CustomUserDetails userDetails,
			@PathVariable(name = "subscriptionId") @Positive Long subscriptionId,
			@Valid @RequestBody RoamingScheduleRequestDto request) {
		return ApiResponse.success(commandService.scheduleRoaming(userDetails.getUserId(), subscriptionId,
				request.roamingProductId(), request.startDate(), request.endDate()));
	}

	@DeleteMapping("/roamings/{subscriptionRoamingId}")
	public ApiResponse<SubscriptionRoamingResponseDto> cancelRoaming(
			@AuthenticationPrincipal CustomUserDetails userDetails,
			@PathVariable(name = "subscriptionId") @Positive Long subscriptionId,
			@PathVariable(name = "subscriptionRoamingId") @Positive Long subscriptionRoamingId) {
		return ApiResponse.success(
				commandService.cancelRoaming(userDetails.getUserId(), subscriptionId, subscriptionRoamingId));
	}

	/** 요청한 회선이 대표 회선이 된다. */
	@PostMapping("/bundles")
	@ResponseStatus(HttpStatus.CREATED)
	public ApiResponse<BundleGroupResponseDto> createBundle(
			@AuthenticationPrincipal CustomUserDetails userDetails,
			@PathVariable(name = "subscriptionId") @Positive Long subscriptionId,
			@Valid @RequestBody BundleJoinRequestDto request) {
		return ApiResponse.success(
				bundleGroupService.create(userDetails.getUserId(), subscriptionId, request.bundleProductId()));
	}
}
