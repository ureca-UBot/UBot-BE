package com.ubot.subscription.controller;

import java.net.URI;

import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import com.ubot.auth.config.CustomUserDetails;
import com.ubot.common.ApiResponse;
import com.ubot.subscription.dto.request.BundleMemberAddRequestDto;
import com.ubot.subscription.dto.response.BundleGroupResponseDto;
import com.ubot.subscription.service.BundleGroupService;

import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import lombok.RequiredArgsConstructor;

/** subscriptionId는 요청한 사용자의 회선이다. 회선 추가는 대표 회선에서만 할 수 있다. */
@RestController
@RequestMapping("/subscriptions/me/{subscriptionId}/bundle-groups/{bundleGroupId}/members")
@RequiredArgsConstructor
@Validated
@Tag(name = "결합 회선 관리")
public class BundleGroupController {

	private final BundleGroupService bundleGroupService;

	@PostMapping
	public ResponseEntity<ApiResponse<BundleGroupResponseDto>> addMember(
			@AuthenticationPrincipal CustomUserDetails userDetails,
			@PathVariable(name = "subscriptionId") @Positive Long subscriptionId,
			@PathVariable(name = "bundleGroupId") @Positive Long bundleGroupId,
			@Valid @RequestBody BundleMemberAddRequestDto request) {
		BundleGroupResponseDto response = bundleGroupService.addMember(
				userDetails.getUserId(), subscriptionId, bundleGroupId, request.phoneNumber());
		return ResponseEntity.created(URI.create(
						"/subscriptions/me/" + subscriptionId + "/bundle-groups/" + bundleGroupId + "/members"))
				.body(ApiResponse.success(response));
	}

	/** 대표 회선을 빼면 결합이 종료되고 data는 null이다. */
	@DeleteMapping("/{memberSubscriptionId}")
	public ApiResponse<BundleGroupResponseDto> removeMember(
			@AuthenticationPrincipal CustomUserDetails userDetails,
			@PathVariable(name = "subscriptionId") @Positive Long subscriptionId,
			@PathVariable(name = "bundleGroupId") @Positive Long bundleGroupId,
			@PathVariable(name = "memberSubscriptionId") @Positive Long memberSubscriptionId) {
		return ApiResponse.success(bundleGroupService.removeMember(
				userDetails.getUserId(), subscriptionId, bundleGroupId, memberSubscriptionId));
	}
}
