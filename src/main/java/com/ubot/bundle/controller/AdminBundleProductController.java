package com.ubot.bundle.controller;

import java.net.URI;

import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import com.ubot.auth.config.CustomUserDetails;
import com.ubot.common.ApiResponse;
import com.ubot.common.PageResponseDto;
import com.ubot.common.enums.MasterStatus;
import com.ubot.common.dto.request.ProductStatusUpdateRequestDto;
import com.ubot.bundle.dto.request.AdminBundleProductCreateRequestDto;
import com.ubot.bundle.dto.request.AdminBundleProductUpdateRequestDto;
import com.ubot.bundle.dto.response.AdminBundleProductResponseDto;
import com.ubot.bundle.service.AdminBundleProductService;

import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/admin/bundle-products")
@RequiredArgsConstructor
@Validated
@Tag(name = "관리자 결합 상품")
public class AdminBundleProductController {

    private final AdminBundleProductService productService;

    @PostMapping
    public ResponseEntity<ApiResponse<AdminBundleProductResponseDto>> createBundleProduct(
            @AuthenticationPrincipal CustomUserDetails administrator,
            @Valid @RequestBody AdminBundleProductCreateRequestDto request
    ) {
        AdminBundleProductResponseDto response = productService.createBundleProduct(administrator.getUserId(), request);
        return ResponseEntity.created(URI.create("/admin/bundle-products/" + response.detail().bundleProductId()))
                .body(ApiResponse.success(response));
    }

    @GetMapping
    public ApiResponse<PageResponseDto<AdminBundleProductResponseDto>> getBundleProductList(
            @RequestParam(name = "keyword", required = false) @Size(max = 100) String keyword,
            @RequestParam(name = "status", required = false) MasterStatus status,
            @RequestParam(name = "deleted", required = false) Boolean deleted,
            @RequestParam(name = "page", defaultValue = "0") @Min(0) int page,
            @RequestParam(name = "size", defaultValue = "20") @Min(1) @Max(100) int size
    ) {
        return ApiResponse.success(productService.getBundleProductList(keyword, status, deleted, page, size));
    }

    @GetMapping("/{bundleProductId}")
    public ApiResponse<AdminBundleProductResponseDto> getBundleProduct(@PathVariable(name = "bundleProductId") @Positive Long bundleProductId) {
        return ApiResponse.success(productService.getBundleProduct(bundleProductId));
    }

    @PutMapping("/{bundleProductId}")
    public ApiResponse<AdminBundleProductResponseDto> updateBundleProduct(
            @AuthenticationPrincipal CustomUserDetails administrator,
            @PathVariable(name = "bundleProductId") @Positive Long bundleProductId,
            @Valid @RequestBody AdminBundleProductUpdateRequestDto request
    ) {
        return ApiResponse.success(productService.updateBundleProduct(administrator.getUserId(), bundleProductId, request));
    }

    @PatchMapping("/{bundleProductId}/status")
    public ApiResponse<AdminBundleProductResponseDto> changeStatus(
            @AuthenticationPrincipal CustomUserDetails administrator,
            @PathVariable(name = "bundleProductId") @Positive Long bundleProductId,
            @Valid @RequestBody ProductStatusUpdateRequestDto request
    ) {
        return ApiResponse.success(productService.changeStatus(administrator.getUserId(), bundleProductId, request));
    }

    @DeleteMapping("/{bundleProductId}")
    public ResponseEntity<Void> deleteBundleProduct(
            @AuthenticationPrincipal CustomUserDetails administrator,
            @PathVariable(name = "bundleProductId") @Positive Long bundleProductId
    ) {
        productService.deleteBundleProduct(administrator.getUserId(), bundleProductId);
        return ResponseEntity.noContent().build();
    }
}
