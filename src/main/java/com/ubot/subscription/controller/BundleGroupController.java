package com.ubot.subscription.controller;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import com.ubot.auth.config.CustomUserDetails;
import com.ubot.common.ApiResponse;
import com.ubot.subscription.dto.request.SubscriptionRequestDtos.BundleMemberAddRequestDto;
import com.ubot.subscription.dto.response.BundleGroupResponseDto;
import com.ubot.subscription.service.BundleGroupService;

import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/bundle-groups/{bundleGroupId}/members")
@RequiredArgsConstructor
@Validated
@Tag(name = "결합 회선 관리")
public class BundleGroupController {

	private final BundleGroupService bundleGroupService;

	@PostMapping
	@ResponseStatus(HttpStatus.CREATED)
	public ApiResponse<BundleGroupResponseDto> addMember(
			@AuthenticationPrincipal CustomUserDetails userDetails,
			@PathVariable(name = "bundleGroupId") @Positive Long bundleGroupId,
			@Valid @RequestBody BundleMemberAddRequestDto request) {
		return ApiResponse.success(
				bundleGroupService.addMember(userDetails.getUserId(), bundleGroupId, request.phoneNumber()));
	}

	/** 대표 회선을 빼면 결합이 종료되고 data는 null이다. */
	@DeleteMapping("/{subscriptionId}")
	public ApiResponse<BundleGroupResponseDto> removeMember(
			@AuthenticationPrincipal CustomUserDetails userDetails,
			@PathVariable(name = "bundleGroupId") @Positive Long bundleGroupId,
			@PathVariable(name = "subscriptionId") @Positive Long subscriptionId) {
		return ApiResponse.success(
				bundleGroupService.removeMember(userDetails.getUserId(), bundleGroupId, subscriptionId));
	}
}
