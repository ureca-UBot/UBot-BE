package com.ubot.inquiry.controller;

import com.ubot.auth.config.CustomUserDetails;
import com.ubot.common.ApiResponse;
import com.ubot.common.PageResponseDto;
import com.ubot.inquiry.dto.response.AdminInquiryResponse;
import com.ubot.inquiry.dto.request.InquiryReplyRequest;
import com.ubot.inquiry.service.InquiryService;
import com.ubot.inquiry.enums.InquiryStatus;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Positive;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/admin/inquiries")
@RequiredArgsConstructor
@Validated
public class AdminInquiryController {
    private final InquiryService inquiryService;

    @GetMapping
    public ApiResponse<PageResponseDto<AdminInquiryResponse>> getAll(
            @RequestParam(required = false) InquiryStatus status,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        return ApiResponse.success(inquiryService.getAll(status, page, size));
    }

    @GetMapping("/{inquiryId}")
    public ApiResponse<AdminInquiryResponse> getOne(@PathVariable @Positive Long inquiryId) {
        return ApiResponse.success(inquiryService.getForAdmin(inquiryId));
    }

    @PatchMapping("/{inquiryId}/reply")
    public ApiResponse<AdminInquiryResponse> reply(
            @AuthenticationPrincipal CustomUserDetails administrator,
            @PathVariable @Positive Long inquiryId,
            @Valid @RequestBody InquiryReplyRequest request) {
        return ApiResponse.success(inquiryService.reply(administrator.getUserId(), inquiryId, request));
    }
}
