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
import com.ubot.subscription.dto.request.SubscriptionCreateRequestDto;
import com.ubot.subscription.dto.response.BundleGroupResponseDto;
import com.ubot.subscription.dto.response.SubscriptionAddonResponseDto;
import com.ubot.subscription.dto.response.SubscriptionResponseDto;
import com.ubot.subscription.dto.response.SubscriptionRoamingResponseDto;
import com.ubot.subscription.service.BundleGroupService;
import com.ubot.subscription.service.SubscriptionCommandService;

import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/subscriptions/me")
@RequiredArgsConstructor
@Validated
@Tag(name = "내 회선 개통·상품 가입·변경")
public class SubscriptionCommandController {

	private final SubscriptionCommandService commandService;
	private final BundleGroupService bundleGroupService;

	/** 회선을 새로 개통한다. phoneNumber를 비우면 서버가 번호를 배정한다. */
	@PostMapping
	public ResponseEntity<ApiResponse<SubscriptionResponseDto>> createSubscription(
			@AuthenticationPrincipal CustomUserDetails userDetails,
			@Valid @RequestBody SubscriptionCreateRequestDto request) {
		SubscriptionResponseDto response = commandService.createSubscription(
				userDetails.getUserId(), request.planId(), request.phoneNumber());
		return ResponseEntity.created(URI.create("/subscriptions/me/" + response.subscriptionId()))
				.body(ApiResponse.success(response));
	}

	/** 회선 해지. 부가서비스·로밍을 취소하고 결합에서 뺀다. 대표 회선이면 결합 전체가 종료된다. */
	@DeleteMapping("/{subscriptionId}")
	public ApiResponse<SubscriptionResponseDto> terminateSubscription(
			@AuthenticationPrincipal CustomUserDetails userDetails,
			@PathVariable(name = "subscriptionId") @Positive Long subscriptionId) {
		return ApiResponse.success(commandService.terminateSubscription(userDetails.getUserId(), subscriptionId));
	}

	@PatchMapping("/{subscriptionId}/plan")
	public ApiResponse<PlanDetailResponseDto> changePlan(
			@AuthenticationPrincipal CustomUserDetails userDetails,
			@PathVariable(name = "subscriptionId") @Positive Long subscriptionId,
			@Valid @RequestBody PlanChangeRequestDto request) {
		return ApiResponse.success(commandService.changePlan(userDetails.getUserId(), subscriptionId, request.planId()));
	}

	@PostMapping("/{subscriptionId}/addons")
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

	@DeleteMapping("/{subscriptionId}/addons/{subscriptionAddonId}")
	public ApiResponse<SubscriptionAddonResponseDto> cancelAddon(
			@AuthenticationPrincipal CustomUserDetails userDetails,
			@PathVariable(name = "subscriptionId") @Positive Long subscriptionId,
			@PathVariable(name = "subscriptionAddonId") @Positive Long subscriptionAddonId) {
		return ApiResponse.success(
				commandService.cancelAddon(userDetails.getUserId(), subscriptionId, subscriptionAddonId));
	}

	@PostMapping("/{subscriptionId}/roamings")
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

	@DeleteMapping("/{subscriptionId}/roamings/{subscriptionRoamingId}")
	public ApiResponse<SubscriptionRoamingResponseDto> cancelRoaming(
			@AuthenticationPrincipal CustomUserDetails userDetails,
			@PathVariable(name = "subscriptionId") @Positive Long subscriptionId,
			@PathVariable(name = "subscriptionRoamingId") @Positive Long subscriptionRoamingId) {
		return ApiResponse.success(
				commandService.cancelRoaming(userDetails.getUserId(), subscriptionId, subscriptionRoamingId));
	}

	/** 요청한 회선이 대표 회선이 된다. */
	@PostMapping("/{subscriptionId}/bundles")
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
