package com.ubot.addon.controller;

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
import com.ubot.addon.dto.request.AdminAddonServiceCreateRequestDto;
import com.ubot.addon.dto.request.AdminAddonServiceUpdateRequestDto;
import com.ubot.addon.dto.response.AdminAddonServiceResponseDto;
import com.ubot.addon.service.AdminAddonService;

import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/admin/addon-services")
@RequiredArgsConstructor
@Validated
@Tag(name = "관리자 부가서비스 상품")
public class AdminAddonServiceController {

    private final AdminAddonService productService;

    @PostMapping
    public ResponseEntity<ApiResponse<AdminAddonServiceResponseDto>> createAddonService(
            @AuthenticationPrincipal CustomUserDetails administrator,
            @Valid @RequestBody AdminAddonServiceCreateRequestDto request
    ) {
        AdminAddonServiceResponseDto response = productService.createAddonService(administrator.getUserId(), request);
        return ResponseEntity.created(URI.create("/admin/addon-services/" + response.detail().addonServiceId()))
                .body(ApiResponse.success(response));
    }

    @GetMapping
    public ApiResponse<PageResponseDto<AdminAddonServiceResponseDto>> getAddonServiceList(
            @RequestParam(name = "keyword", required = false) @Size(max = 100) String keyword,
            @RequestParam(name = "status", required = false) MasterStatus status,
            @RequestParam(name = "deleted", required = false) Boolean deleted,
            @RequestParam(name = "page", defaultValue = "0") @Min(0) int page,
            @RequestParam(name = "size", defaultValue = "20") @Min(1) @Max(100) int size
    ) {
        return ApiResponse.success(productService.getAddonServiceList(keyword, status, deleted, page, size));
    }

    @GetMapping("/{addonServiceId}")
    public ApiResponse<AdminAddonServiceResponseDto> getAddonService(@PathVariable(name = "addonServiceId") @Positive Long addonServiceId) {
        return ApiResponse.success(productService.getAddonService(addonServiceId));
    }

    @PutMapping("/{addonServiceId}")
    public ApiResponse<AdminAddonServiceResponseDto> updateAddonService(
            @AuthenticationPrincipal CustomUserDetails administrator,
            @PathVariable(name = "addonServiceId") @Positive Long addonServiceId,
            @Valid @RequestBody AdminAddonServiceUpdateRequestDto request
    ) {
        return ApiResponse.success(productService.updateAddonService(administrator.getUserId(), addonServiceId, request));
    }

    @PatchMapping("/{addonServiceId}/status")
    public ApiResponse<AdminAddonServiceResponseDto> changeStatus(
            @AuthenticationPrincipal CustomUserDetails administrator,
            @PathVariable(name = "addonServiceId") @Positive Long addonServiceId,
            @Valid @RequestBody ProductStatusUpdateRequestDto request
    ) {
        return ApiResponse.success(productService.changeStatus(administrator.getUserId(), addonServiceId, request));
    }

    @DeleteMapping("/{addonServiceId}")
    public ResponseEntity<Void> deleteAddonService(
            @AuthenticationPrincipal CustomUserDetails administrator,
            @PathVariable(name = "addonServiceId") @Positive Long addonServiceId
    ) {
        productService.deleteAddonService(administrator.getUserId(), addonServiceId);
        return ResponseEntity.noContent().build();
    }
}
