package com.ubot.contract.controller;

import java.util.List;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.ubot.auth.config.CustomUserDetails;
import com.ubot.common.ApiResponse;
import com.ubot.contract.dto.response.SubscriptionContractResponseDto;
import com.ubot.contract.service.ContractService;

import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Positive;
import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/subscriptions/me/{subscriptionId}")
@RequiredArgsConstructor
@Validated
@Tag(name = "내 약정·단말 할부 조회")
public class ContractController {
	private final ContractService contractService;

	@GetMapping("/contract")
	public ApiResponse<SubscriptionContractResponseDto> getMyContract(
			@AuthenticationPrincipal CustomUserDetails userDetails,
			@PathVariable(name = "subscriptionId") @Positive Long subscriptionId) {
		return ApiResponse.success(contractService.getMyContract(userDetails.getUserId(), subscriptionId));
	}

	@GetMapping("/contracts")
	public ApiResponse<List<SubscriptionContractResponseDto>> getMyContractList(
			@AuthenticationPrincipal CustomUserDetails userDetails,
			@PathVariable(name = "subscriptionId") @Positive Long subscriptionId) {
		return ApiResponse.success(contractService.getMyContractList(userDetails.getUserId(), subscriptionId));
	}
}
