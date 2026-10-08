package com.ubot.roaming.controller;

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
import com.ubot.roaming.dto.request.AdminRoamingProductCreateRequestDto;
import com.ubot.roaming.dto.request.AdminRoamingProductUpdateRequestDto;
import com.ubot.roaming.dto.response.AdminRoamingProductResponseDto;
import com.ubot.roaming.service.AdminRoamingProductService;

import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/admin/roaming-products")
@RequiredArgsConstructor
@Validated
@Tag(name = "관리자 로밍 상품")
public class AdminRoamingProductController {

    private final AdminRoamingProductService productService;

    @PostMapping
    public ResponseEntity<ApiResponse<AdminRoamingProductResponseDto>> createRoamingProduct(
            @AuthenticationPrincipal CustomUserDetails administrator,
            @Valid @RequestBody AdminRoamingProductCreateRequestDto request
    ) {
        AdminRoamingProductResponseDto response = productService.createRoamingProduct(administrator.getUserId(), request);
        return ResponseEntity.created(URI.create("/admin/roaming-products/" + response.detail().roamingProductId()))
                .body(ApiResponse.success(response));
    }

    @GetMapping
    public ApiResponse<PageResponseDto<AdminRoamingProductResponseDto>> getRoamingProductList(
            @RequestParam(name = "keyword", required = false) @Size(max = 100) String keyword,
            @RequestParam(name = "status", required = false) MasterStatus status,
            @RequestParam(name = "deleted", required = false) Boolean deleted,
            @RequestParam(name = "page", defaultValue = "0") @Min(0) int page,
            @RequestParam(name = "size", defaultValue = "20") @Min(1) @Max(100) int size
    ) {
        return ApiResponse.success(productService.getRoamingProductList(keyword, status, deleted, page, size));
    }

    @GetMapping("/{roamingProductId}")
    public ApiResponse<AdminRoamingProductResponseDto> getRoamingProduct(@PathVariable(name = "roamingProductId") @Positive Long roamingProductId) {
        return ApiResponse.success(productService.getRoamingProduct(roamingProductId));
    }

    @PutMapping("/{roamingProductId}")
    public ApiResponse<AdminRoamingProductResponseDto> updateRoamingProduct(
            @AuthenticationPrincipal CustomUserDetails administrator,
            @PathVariable(name = "roamingProductId") @Positive Long roamingProductId,
            @Valid @RequestBody AdminRoamingProductUpdateRequestDto request
    ) {
        return ApiResponse.success(productService.updateRoamingProduct(administrator.getUserId(), roamingProductId, request));
    }

    @PatchMapping("/{roamingProductId}/status")
    public ApiResponse<AdminRoamingProductResponseDto> changeStatus(
            @AuthenticationPrincipal CustomUserDetails administrator,
            @PathVariable(name = "roamingProductId") @Positive Long roamingProductId,
            @Valid @RequestBody ProductStatusUpdateRequestDto request
    ) {
        return ApiResponse.success(productService.changeStatus(administrator.getUserId(), roamingProductId, request));
    }

    @DeleteMapping("/{roamingProductId}")
    public ResponseEntity<Void> deleteRoamingProduct(
            @AuthenticationPrincipal CustomUserDetails administrator,
            @PathVariable(name = "roamingProductId") @Positive Long roamingProductId
    ) {
        productService.deleteRoamingProduct(administrator.getUserId(), roamingProductId);
        return ResponseEntity.noContent().build();
    }
}
