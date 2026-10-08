package com.ubot.plan.controller;

import java.net.URI;

import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import com.ubot.auth.config.CustomUserDetails;
import com.ubot.common.ApiResponse;
import com.ubot.common.PageResponseDto;
import com.ubot.common.enums.MasterStatus;
import com.ubot.plan.dto.request.AdminPlanCreateRequestDto;
import com.ubot.plan.dto.request.AdminPlanUpdateRequestDto;
import com.ubot.plan.dto.request.AdminPlanStatusUpdateRequestDto;
import com.ubot.plan.dto.response.AdminPlanResponseDto;
import com.ubot.plan.service.AdminPlanService;

import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/admin/plans")
@RequiredArgsConstructor
@Validated
@Tag(name = "관리자 요금제")
public class AdminPlanController {

    private final AdminPlanService productService;

    @PostMapping
    public ResponseEntity<ApiResponse<AdminPlanResponseDto>> createPlan(
            @AuthenticationPrincipal CustomUserDetails administrator,
            @Valid @RequestBody AdminPlanCreateRequestDto request
    ) {
        AdminPlanResponseDto response = productService.createPlan(administrator.getUserId(), request);
        return ResponseEntity.created(URI.create("/admin/plans/" + response.detail().summary().planId()))
                .body(ApiResponse.success(response));
    }

    @GetMapping
    public ApiResponse<PageResponseDto<AdminPlanResponseDto>> getPlanList(
            @RequestParam(name = "keyword", required = false) @Size(max = 100) String keyword,
            @RequestParam(name = "status", required = false) MasterStatus status,
            @RequestParam(name = "deleted", required = false) Boolean deleted,
            @RequestParam(name = "page", defaultValue = "0") @Min(0) int page,
            @RequestParam(name = "size", defaultValue = "20") @Min(1) @Max(100) int size
    ) {
        return ApiResponse.success(productService.getPlanList(keyword, status, deleted, page, size));
    }

    @GetMapping("/{planId}")
    public ApiResponse<AdminPlanResponseDto> getPlan(@PathVariable(name = "planId") @Positive Long planId) {
        return ApiResponse.success(productService.getPlan(planId));
    }

    @PutMapping("/{planId}")
    public ApiResponse<AdminPlanResponseDto> updatePlan(
            @AuthenticationPrincipal CustomUserDetails administrator,
            @PathVariable(name = "planId") @Positive Long planId,
            @Valid @RequestBody AdminPlanUpdateRequestDto request
    ) {
        return ApiResponse.success(productService.updatePlan(administrator.getUserId(), planId, request));
    }

    @PatchMapping("/{planId}/status")
    public ApiResponse<AdminPlanResponseDto> changeStatus(
            @AuthenticationPrincipal CustomUserDetails administrator,
            @PathVariable(name = "planId") @Positive Long planId,
            @Valid @RequestBody AdminPlanStatusUpdateRequestDto request
    ) {
        return ApiResponse.success(productService.changeStatus(administrator.getUserId(), planId, request));
    }

    @DeleteMapping("/{planId}")
    public ResponseEntity<Void> deletePlan(
            @AuthenticationPrincipal CustomUserDetails administrator,
            @PathVariable(name = "planId") @Positive Long planId
    ) {
        productService.deletePlan(administrator.getUserId(), planId);
        return ResponseEntity.noContent().build();
    }
}
