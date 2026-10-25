package com.ubot.telemap.controller;

import com.ubot.auth.config.CustomUserDetails;
import com.ubot.common.ApiResponse;
import com.ubot.common.PageResponseDto;
import com.ubot.ranking.enums.RegionSido;
import com.ubot.telemap.dto.response.SurgingAlertDetailResponse;
import com.ubot.telemap.dto.response.SurgingAlertSummaryResponse;
import com.ubot.telemap.enums.SurgingAlertStatus;
import com.ubot.telemap.service.TelemapAlertService;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Positive;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@Validated
@RequestMapping("/admin/telemap/alerts")
public class TelemapAlertController {

    private final TelemapAlertService telemapAlertService;

    @GetMapping
    public ApiResponse<PageResponseDto<SurgingAlertSummaryResponse>> getAlerts(
            @RequestParam(required = false) SurgingAlertStatus status,
            @RequestParam(required = false) RegionSido region,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "10") @Min(1) @Max(100) int size
    ) {
        return ApiResponse.success(telemapAlertService.getAlerts(status, region, page, size));
    }

    @GetMapping("/{alertId}")
    public ApiResponse<SurgingAlertDetailResponse> getAlert(
            @PathVariable("alertId") @Positive Long alertId
    ) {
        return ApiResponse.success(telemapAlertService.getAlert(alertId));
    }

    @PatchMapping("/{alertId}/processed")
    public ApiResponse<SurgingAlertDetailResponse> markProcessed(
            @PathVariable("alertId") @Positive Long alertId,
            @AuthenticationPrincipal CustomUserDetails administrator
    ) {
        return ApiResponse.success(telemapAlertService.markProcessed(alertId, administrator.getUserId()));
    }
}
