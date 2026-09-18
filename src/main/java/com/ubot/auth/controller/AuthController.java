package com.ubot.auth.controller;


import com.ubot.auth.config.CustomUserDetails;
import com.ubot.auth.dto.request.LoginRequestDto;
import com.ubot.auth.dto.request.RefreshTokenRequestDto;
import com.ubot.auth.dto.request.SignupRequestDto;
import com.ubot.auth.dto.response.LoginResponseDto;
import com.ubot.auth.service.AuthService;
import com.ubot.common.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/auth")
@RequiredArgsConstructor
public class AuthController {
	private final AuthService authService;

	@PostMapping("/login")
	public ApiResponse<LoginResponseDto> loginUser(
			@RequestBody LoginRequestDto requestDto){
		return ApiResponse.success(authService.loginUser(requestDto));
	}

	@PostMapping("/refresh")
	public ApiResponse<LoginResponseDto> refreshAccessToken(
			@RequestBody RefreshTokenRequestDto requestDto
	){
		return ApiResponse.success(authService.refreshAccessToken(requestDto));
	}

	@PostMapping("/logout")
	public ApiResponse<Void> logoutUser(
			@AuthenticationPrincipal CustomUserDetails userDetails
	){
		authService.logoutUser(userDetails.getUserId());
		return ApiResponse.success("로그아웃이 정상적으로 처리됐습니다.", null);
	}

	@PostMapping("/signup")
	public ApiResponse<Void> signupUser(
			@RequestBody SignupRequestDto requestDto
	){
		authService.signupUser(requestDto);
		return ApiResponse.success("회원가입이 성공했습니다.", null);
	}
}
