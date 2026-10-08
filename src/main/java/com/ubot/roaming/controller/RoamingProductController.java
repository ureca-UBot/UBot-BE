package com.ubot.roaming.controller;

import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import com.ubot.common.ApiResponse;
import com.ubot.common.PageResponseDto;
import com.ubot.roaming.dto.response.RoamingProductResponseDto;
import com.ubot.roaming.service.RoamingCatalogService;

import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.*;
import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/roaming-products")
@RequiredArgsConstructor
@Validated
@Tag(name = "로밍 상품 조회")
public class RoamingProductController {

    private final RoamingCatalogService productService;

    @GetMapping
    public ApiResponse<PageResponseDto<RoamingProductResponseDto>> getRoamingProductList(
            @RequestParam(name = "keyword", required = false) @Size(max = 100) String keyword,
            @RequestParam(name = "country", required = false) @Size(max = 100) String country,
            @RequestParam(name = "page", defaultValue = "0") @Min(0) int page,
            @RequestParam(name = "size", defaultValue = "20") @Min(1) @Max(100) int size
    ) {
        return ApiResponse.success(productService.getRoamingProductList(keyword, country, page, size));
    }

    @GetMapping("/{roamingProductId}")
    public ApiResponse<RoamingProductResponseDto> getRoamingProduct(@PathVariable(name = "roamingProductId") @Positive Long roamingProductId) {
        return ApiResponse.success(productService.getRoamingProduct(roamingProductId));
    }
}
