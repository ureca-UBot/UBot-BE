package com.ubot.user.controller;

import com.ubot.auth.config.CustomUserDetails;
import com.ubot.common.ApiResponse;
import com.ubot.user.dto.request.UserUpdateRequestDto;
import com.ubot.user.dto.response.UserResponseDto;
import com.ubot.user.service.UserService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/auth/me")
@RequiredArgsConstructor
public class UserController {
	private final UserService userService;

	@GetMapping
	public ApiResponse<UserResponseDto> getUser(
			@AuthenticationPrincipal CustomUserDetails userDetails
	){
		return ApiResponse.success(userService.getActiveUser(userDetails.getUserId()));
	}

	@PatchMapping
	public ApiResponse<UserResponseDto> updateUser(
			@AuthenticationPrincipal CustomUserDetails userDetails,
			@Valid @RequestBody UserUpdateRequestDto requestDto
	){
		return ApiResponse.success(userService.updateActiveUser(userDetails.getUserId(), requestDto));
	}
}
