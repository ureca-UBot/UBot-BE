package com.ubot.unanswered.controller;

import com.ubot.auth.config.CustomUserDetails;
import com.ubot.common.ApiResponse;
import com.ubot.common.PageResponseDto;
import com.ubot.unanswered.dto.request.UnansweredGroupFaqCreateRequestDto;
import com.ubot.unanswered.dto.request.UnansweredGroupStatusUpdateRequestDto;
import com.ubot.unanswered.dto.response.UnansweredGroupDetailResponseDto;
import com.ubot.unanswered.dto.response.UnansweredGroupResponseDto;
import com.ubot.unanswered.enums.UnansweredGroupStatus;
import com.ubot.unanswered.service.UnansweredGroupService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Positive;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/admin/unanswered-groups")
@RequiredArgsConstructor
@Validated
public class AdminUnansweredGroupController {
	private final UnansweredGroupService unansweredGroupService;

	@GetMapping
	public ApiResponse<PageResponseDto<UnansweredGroupResponseDto>> getUnansweredGroupList(
			@RequestParam(name = "status", required = false) UnansweredGroupStatus status,
			@RequestParam(name = "minCount", defaultValue = "2") @Min(1) int minCount,
			@RequestParam(name = "sort", defaultValue = "recent") String sort,
			@RequestParam(name = "page", defaultValue = "0") @Min(0) int page,
			@RequestParam(name = "size", defaultValue = "20") int size
	){
		return ApiResponse.success(unansweredGroupService.getUnansweredGroupList(status, minCount, sort, page, size));
	}

	@GetMapping("/{groupId}")
	public ApiResponse<UnansweredGroupDetailResponseDto> getUnansweredGroup(
			@Positive @PathVariable("groupId") Long groupId
	){
		return ApiResponse.success(unansweredGroupService.getUnansweredGroup(groupId));
	}

	@PostMapping("/{groupId}/faq")
	public ApiResponse<UnansweredGroupResponseDto> createUnansweredGroupFaq(
			@Positive @PathVariable("groupId") Long groupId,
			@Valid @RequestBody UnansweredGroupFaqCreateRequestDto requestDto,
			@AuthenticationPrincipal CustomUserDetails userDetails
	){
		return ApiResponse.success(
				unansweredGroupService.createUnansweredGroupFaq(groupId, requestDto, userDetails.getUserId())
		);
	}

	@PatchMapping("/{groupId}/status")
	public ApiResponse<UnansweredGroupResponseDto> updateUnansweredGroupStatus(
			@Positive @PathVariable("groupId") Long groupId,
			@Valid @RequestBody UnansweredGroupStatusUpdateRequestDto requestDto
	){
		return ApiResponse.success(unansweredGroupService.updateUnansweredGroupStatus(groupId, requestDto));
	}
}
