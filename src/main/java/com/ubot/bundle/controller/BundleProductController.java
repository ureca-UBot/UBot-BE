package com.ubot.bundle.controller;

import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import com.ubot.common.ApiResponse;
import com.ubot.common.PageResponseDto;
import com.ubot.bundle.dto.response.BundleProductResponseDto;
import com.ubot.bundle.service.BundleProductService;

import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.*;
import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/bundle-products")
@RequiredArgsConstructor
@Validated
@Tag(name = "결합 상품 조회")
public class BundleProductController {

    private final BundleProductService productService;

    @GetMapping
    public ApiResponse<PageResponseDto<BundleProductResponseDto>> getBundleProductList(
            @RequestParam(name = "keyword", required = false) @Size(max = 100) String keyword,
            @RequestParam(name = "page", defaultValue = "0") @Min(0) int page,
            @RequestParam(name = "size", defaultValue = "20") @Min(1) @Max(100) int size
    ) {
        return ApiResponse.success(productService.getBundleProductList(keyword, page, size));
    }

    @GetMapping("/{bundleProductId}")
    public ApiResponse<BundleProductResponseDto> getBundleProduct(@PathVariable(name = "bundleProductId") @Positive Long bundleProductId) {
        return ApiResponse.success(productService.getBundleProduct(bundleProductId));
    }
}
