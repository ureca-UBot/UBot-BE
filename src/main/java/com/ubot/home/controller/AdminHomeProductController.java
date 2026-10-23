package com.ubot.home.controller;

import java.net.URI;

import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import com.ubot.auth.config.CustomUserDetails;
import com.ubot.common.ApiResponse;
import com.ubot.common.PageResponseDto;
import com.ubot.common.dto.request.ProductStatusUpdateRequestDto;
import com.ubot.common.enums.MasterStatus;
import com.ubot.home.dto.request.AdminHomeProductCreateRequestDto;
import com.ubot.home.dto.request.AdminHomeProductUpdateRequestDto;
import com.ubot.home.dto.response.AdminHomeProductResponseDto;
import com.ubot.home.service.AdminHomeProductService;

import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/admin/home-products")
@RequiredArgsConstructor
@Validated
@Tag(name = "관리자 인터넷/IPTV 상품")
public class AdminHomeProductController {

    private final AdminHomeProductService productService;

    @PostMapping
    public ResponseEntity<ApiResponse<AdminHomeProductResponseDto>> createHomeProduct(
            @AuthenticationPrincipal CustomUserDetails administrator,
            @Valid @RequestBody AdminHomeProductCreateRequestDto request
    ) {
        AdminHomeProductResponseDto response = productService.createHomeProduct(administrator.getUserId(), request);
        return ResponseEntity.created(URI.create("/admin/home-products/" + response.detail().homeProductId()))
                .body(ApiResponse.success(response));
    }

    @GetMapping
    public ApiResponse<PageResponseDto<AdminHomeProductResponseDto>> getHomeProductList(
            @RequestParam(name = "keyword", required = false) @Size(max = 100) String keyword,
            @RequestParam(name = "status", required = false) MasterStatus status,
            @RequestParam(name = "deleted", required = false) Boolean deleted,
            @RequestParam(name = "page", defaultValue = "0") @Min(0) int page,
            @RequestParam(name = "size", defaultValue = "20") @Min(1) @Max(100) int size
    ) {
        return ApiResponse.success(productService.getHomeProductList(keyword, status, deleted, page, size));
    }

    @GetMapping("/{homeProductId}")
    public ApiResponse<AdminHomeProductResponseDto> getHomeProduct(@PathVariable(name = "homeProductId") @Positive Long homeProductId) {
        return ApiResponse.success(productService.getHomeProduct(homeProductId));
    }

    @PutMapping("/{homeProductId}")
    public ApiResponse<AdminHomeProductResponseDto> updateHomeProduct(
            @AuthenticationPrincipal CustomUserDetails administrator,
            @PathVariable(name = "homeProductId") @Positive Long homeProductId,
            @Valid @RequestBody AdminHomeProductUpdateRequestDto request
    ) {
        return ApiResponse.success(productService.updateHomeProduct(administrator.getUserId(), homeProductId, request));
    }

    @PatchMapping("/{homeProductId}/status")
    public ApiResponse<AdminHomeProductResponseDto> changeStatus(
            @AuthenticationPrincipal CustomUserDetails administrator,
            @PathVariable(name = "homeProductId") @Positive Long homeProductId,
            @Valid @RequestBody ProductStatusUpdateRequestDto request
    ) {
        return ApiResponse.success(productService.changeStatus(administrator.getUserId(), homeProductId, request));
    }

    @DeleteMapping("/{homeProductId}")
    public ResponseEntity<Void> deleteHomeProduct(
            @AuthenticationPrincipal CustomUserDetails administrator,
            @PathVariable(name = "homeProductId") @Positive Long homeProductId
    ) {
        productService.deleteHomeProduct(administrator.getUserId(), homeProductId);
        return ResponseEntity.noContent().build();
    }
}
