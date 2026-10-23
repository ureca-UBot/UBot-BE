package com.ubot.home.controller;

import java.net.URI;
import java.util.List;

import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import com.ubot.auth.config.CustomUserDetails;
import com.ubot.common.ApiResponse;
import com.ubot.home.dto.request.HomeSubscribeRequestDto;
import com.ubot.home.dto.response.HomeSubscriptionResponseDto;
import com.ubot.home.service.HomeSubscriptionService;

import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/home-subscriptions/me")
@RequiredArgsConstructor
@Validated
@Tag(name = "내 인터넷/IPTV 가입")
public class HomeSubscriptionController {

    private final HomeSubscriptionService subscriptionService;

    @GetMapping
    public ApiResponse<List<HomeSubscriptionResponseDto>> getMyHomeSubscriptionList(
            @AuthenticationPrincipal CustomUserDetails userDetails) {
        return ApiResponse.success(subscriptionService.getMyHomeSubscriptionList(userDetails.getUserId()));
    }

    @PostMapping
    public ResponseEntity<ApiResponse<HomeSubscriptionResponseDto>> subscribe(
            @AuthenticationPrincipal CustomUserDetails userDetails,
            @Valid @RequestBody HomeSubscribeRequestDto request) {
        HomeSubscriptionResponseDto response = subscriptionService.subscribe(userDetails.getUserId(), request.homeProductId());
        return ResponseEntity.created(URI.create("/home-subscriptions/me/" + response.homeSubscriptionId()))
                .body(ApiResponse.success(response));
    }

    @DeleteMapping("/{homeSubscriptionId}")
    public ApiResponse<HomeSubscriptionResponseDto> end(
            @AuthenticationPrincipal CustomUserDetails userDetails,
            @PathVariable(name = "homeSubscriptionId") @Positive Long homeSubscriptionId) {
        return ApiResponse.success(subscriptionService.end(userDetails.getUserId(), homeSubscriptionId));
    }
}
