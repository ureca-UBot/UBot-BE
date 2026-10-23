package com.ubot.home.controller;

import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import com.ubot.common.ApiResponse;
import com.ubot.common.PageResponseDto;
import com.ubot.home.dto.response.HomeProductResponseDto;
import com.ubot.home.enums.HomeProductType;
import com.ubot.home.service.HomeProductService;

import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.*;
import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/home-products")
@RequiredArgsConstructor
@Validated
@Tag(name = "인터넷/IPTV 상품 조회")
public class HomeProductController {

    private final HomeProductService productService;

    @GetMapping
    public ApiResponse<PageResponseDto<HomeProductResponseDto>> getHomeProductList(
            @RequestParam(name = "keyword", required = false) @Size(max = 100) String keyword,
            @RequestParam(name = "productType", required = false) HomeProductType productType,
            @RequestParam(name = "page", defaultValue = "0") @Min(0) int page,
            @RequestParam(name = "size", defaultValue = "20") @Min(1) @Max(100) int size
    ) {
        return ApiResponse.success(productService.getHomeProductList(keyword, productType, page, size));
    }

    @GetMapping("/{homeProductId}")
    public ApiResponse<HomeProductResponseDto> getHomeProduct(@PathVariable(name = "homeProductId") @Positive Long homeProductId) {
        return ApiResponse.success(productService.getHomeProduct(homeProductId));
    }
}
