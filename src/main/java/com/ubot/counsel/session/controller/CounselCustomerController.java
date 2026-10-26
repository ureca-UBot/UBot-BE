package com.ubot.counsel.session.controller;

import com.ubot.auth.config.CustomUserDetails;
import com.ubot.common.ApiResponse;
import com.ubot.counsel.auth.model.Caller;
import com.ubot.counsel.session.dto.response.CustomerInfoResponseDto;
import com.ubot.counsel.session.service.CounselCustomerService;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/counsel/sessions")
@RequiredArgsConstructor
public class CounselCustomerController {
	private final CounselCustomerService service;

	/** 배정된 상담사가 진행 중인 상담의 고객 정보를 조회합니다. */
	@GetMapping("/{id}/customer")
	public ApiResponse<CustomerInfoResponseDto> getCustomer(@AuthenticationPrincipal CustomUserDetails principal,
			@PathVariable UUID id) {
		return ApiResponse.success(service.getCustomer(Caller.from(principal), id));
	}
}
