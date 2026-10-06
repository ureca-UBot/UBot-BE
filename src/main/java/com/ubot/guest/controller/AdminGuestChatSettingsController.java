package com.ubot.guest.controller;

import com.ubot.auth.config.CustomUserDetails;
import com.ubot.common.ApiResponse;
import com.ubot.guest.dto.request.GuestChatSettingsUpdateRequestDto;
import com.ubot.guest.dto.response.GuestChatSettingsResponseDto;
import com.ubot.guest.service.GuestChatSettingsService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/admin/guest-chat-settings")
@RequiredArgsConstructor
public class AdminGuestChatSettingsController {
	private final GuestChatSettingsService guestChatSettingsService;

	@GetMapping
	public ApiResponse<GuestChatSettingsResponseDto> getGuestChatSettings() {
		return ApiResponse.success(guestChatSettingsService.getGuestChatSettings());
	}

	@PatchMapping
	public ApiResponse<GuestChatSettingsResponseDto> updateGuestChatSettings(
			@Valid @RequestBody GuestChatSettingsUpdateRequestDto requestDto,
			@AuthenticationPrincipal CustomUserDetails userDetails
	) {
		return ApiResponse.success(
				guestChatSettingsService.updateGuestChatSettings(requestDto, userDetails.getUserId())
		);
	}
}
