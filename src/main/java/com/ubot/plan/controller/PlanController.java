package com.ubot.plan.controller;

import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import com.ubot.common.ApiResponse;
import com.ubot.common.PageResponseDto;
import com.ubot.plan.dto.response.PlanDetailResponseDto;
import com.ubot.plan.dto.response.PlanSummaryResponseDto;
import com.ubot.plan.service.PlanService;

import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.*;
import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/plans")
@RequiredArgsConstructor
@Validated
@Tag(name = "요금제 조회")
public class PlanController {

    private final PlanService productService;

    @GetMapping
    public ApiResponse<PageResponseDto<PlanSummaryResponseDto>> getPlanList(
            @RequestParam(name = "keyword", required = false) @Size(max = 100) String keyword,
            @RequestParam(name = "page", defaultValue = "0") @Min(0) int page,
            @RequestParam(name = "size", defaultValue = "20") @Min(1) @Max(100) int size
    ) {
        return ApiResponse.success(productService.getPlanList(keyword, page, size));
    }

    @GetMapping("/{planId}")
    public ApiResponse<PlanDetailResponseDto> getPlan(@PathVariable(name = "planId") @Positive Long planId) {
        return ApiResponse.success(productService.getPlan(planId));
    }
}
