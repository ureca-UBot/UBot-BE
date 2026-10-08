package com.ubot.addon.controller;

import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import com.ubot.common.ApiResponse;
import com.ubot.common.PageResponseDto;
import com.ubot.addon.dto.response.AddonServiceResponseDto;
import com.ubot.addon.service.AddonCatalogService;

import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.*;
import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/addon-services")
@RequiredArgsConstructor
@Validated
@Tag(name = "부가서비스 상품 조회")
public class AddonServiceController {

    private final AddonCatalogService productService;

    @GetMapping
    public ApiResponse<PageResponseDto<AddonServiceResponseDto>> getAddonServiceList(
            @RequestParam(name = "keyword", required = false) @Size(max = 100) String keyword,
            @RequestParam(name = "page", defaultValue = "0") @Min(0) int page,
            @RequestParam(name = "size", defaultValue = "20") @Min(1) @Max(100) int size
    ) {
        return ApiResponse.success(productService.getAddonServiceList(keyword, page, size));
    }

    @GetMapping("/{addonServiceId}")
    public ApiResponse<AddonServiceResponseDto> getAddonService(@PathVariable(name = "addonServiceId") @Positive Long addonServiceId) {
        return ApiResponse.success(productService.getAddonService(addonServiceId));
    }
}
