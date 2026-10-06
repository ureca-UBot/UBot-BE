package com.ubot.notification.controller;

import com.ubot.auth.config.CustomUserDetails;
import com.ubot.common.ApiResponse;
import com.ubot.notification.dto.response.NotificationResponseDto;
import com.ubot.notification.service.NotificationService;
import jakarta.validation.constraints.Positive;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/notifications")
@RequiredArgsConstructor
@Validated
public class NotificationController {
	private final NotificationService notificationService;

	@GetMapping("/me")
	public ApiResponse<List<NotificationResponseDto>> getMyNotificationList(
			@AuthenticationPrincipal CustomUserDetails userDetails
	){
		return ApiResponse.success(notificationService.getMyNotificationList(userDetails.getUserId()));
	}

	@PatchMapping("/{notificationId}/read")
	public ApiResponse<NotificationResponseDto> readNotification(
			@Positive @PathVariable("notificationId") Long notificationId,
			@AuthenticationPrincipal CustomUserDetails userDetails
	){
		return ApiResponse.success(notificationService.readNotification(userDetails.getUserId(), notificationId));
	}
}
