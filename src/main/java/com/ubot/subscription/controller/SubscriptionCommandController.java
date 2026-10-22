package com.ubot.subscription.controller;

import java.net.URI;

import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import com.ubot.auth.config.CustomUserDetails;
import com.ubot.common.ApiResponse;
import com.ubot.plan.dto.response.PlanDetailResponseDto;
import com.ubot.subscription.dto.request.AddonSubscribeRequestDto;
import com.ubot.subscription.dto.request.BundleJoinRequestDto;
import com.ubot.subscription.dto.request.PlanChangeRequestDto;
import com.ubot.subscription.dto.request.RoamingScheduleRequestDto;
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
	public ResponseEntity<ApiResponse<SubscriptionAddonResponseDto>> subscribeAddon(
			@AuthenticationPrincipal CustomUserDetails userDetails,
			@PathVariable(name = "subscriptionId") @Positive Long subscriptionId,
			@Valid @RequestBody AddonSubscribeRequestDto request) {
		SubscriptionAddonResponseDto response =
				commandService.subscribeAddon(userDetails.getUserId(), subscriptionId, request.addonServiceId());
		return ResponseEntity.created(URI.create(
						"/subscriptions/me/" + subscriptionId + "/addons/" + response.subscriptionAddonId()))
				.body(ApiResponse.success(response));
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
	public ResponseEntity<ApiResponse<SubscriptionRoamingResponseDto>> scheduleRoaming(
			@AuthenticationPrincipal CustomUserDetails userDetails,
			@PathVariable(name = "subscriptionId") @Positive Long subscriptionId,
			@Valid @RequestBody RoamingScheduleRequestDto request) {
		SubscriptionRoamingResponseDto response = commandService.scheduleRoaming(userDetails.getUserId(),
				subscriptionId, request.roamingProductId(), request.startDate(), request.endDate());
		return ResponseEntity.created(URI.create(
						"/subscriptions/me/" + subscriptionId + "/roamings/" + response.subscriptionRoamingId()))
				.body(ApiResponse.success(response));
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
	public ResponseEntity<ApiResponse<BundleGroupResponseDto>> createBundle(
			@AuthenticationPrincipal CustomUserDetails userDetails,
			@PathVariable(name = "subscriptionId") @Positive Long subscriptionId,
			@Valid @RequestBody BundleJoinRequestDto request) {
		BundleGroupResponseDto response =
				bundleGroupService.create(userDetails.getUserId(), subscriptionId, request.bundleProductId());
		return ResponseEntity.created(URI.create(
						"/subscriptions/me/" + subscriptionId + "/bundle-groups/" + response.bundleGroupId() + "/members"))
				.body(ApiResponse.success(response));
	}
}
