package com.ubot.forbiddenword.controller;

import com.ubot.common.ApiResponse;
import com.ubot.common.PageResponseDto;
import com.ubot.forbiddenword.dto.request.ForbiddenWordCreateRequestDto;
import com.ubot.forbiddenword.dto.request.ForbiddenWordUpdateRequestDto;
import com.ubot.forbiddenword.dto.response.ForbiddenWordResponseDto;
import com.ubot.forbiddenword.service.ForbiddenWordService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Positive;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;


@RestController
@RequestMapping("/admin/forbidden-words")
@RequiredArgsConstructor
@Validated
public class AdminForbiddenWordController {
	private final ForbiddenWordService forbiddenWordService;

	@PostMapping
	public ApiResponse<ForbiddenWordResponseDto> createForbiddenWord(
			@Valid @RequestBody ForbiddenWordCreateRequestDto requestDto
	) {
		return ApiResponse.success(forbiddenWordService.createForbiddenWord(requestDto));
	}

	@GetMapping
	public ApiResponse<PageResponseDto<ForbiddenWordResponseDto>> getForbiddenWordList(
			@RequestParam(defaultValue = "0") @Min(0) int page,
			@RequestParam(defaultValue = "20") int size
	) {
		return ApiResponse.success(forbiddenWordService.getForbiddenWordList(page, size));
	}

	@PatchMapping("/{forbiddenWordId}")
	public ApiResponse<ForbiddenWordResponseDto> updateForbiddenWord(
			@Positive @PathVariable("forbiddenWordId") Long forbiddenWordId,
			@Valid @RequestBody ForbiddenWordUpdateRequestDto requestDto
	) {
		return ApiResponse.success(forbiddenWordService.updateForbiddenWord(forbiddenWordId, requestDto));
	}
}
