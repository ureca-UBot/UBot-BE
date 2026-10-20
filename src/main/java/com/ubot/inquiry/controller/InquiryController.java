package com.ubot.inquiry.controller;

import com.ubot.auth.config.CustomUserDetails;
import com.ubot.common.ApiResponse;
import com.ubot.common.PageResponseDto;
import com.ubot.inquiry.dto.request.InquiryCreateRequest;
import com.ubot.inquiry.dto.response.InquiryResponse;
import com.ubot.inquiry.service.InquiryService;
import com.ubot.inquiry.dto.request.InquiryUpdateRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Positive;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/inquiries")
@RequiredArgsConstructor
@Validated
public class InquiryController {
    private final InquiryService inquiryService;

    @PostMapping
    public ApiResponse<InquiryResponse> create(
            @AuthenticationPrincipal CustomUserDetails user,
            @Valid @RequestBody InquiryCreateRequest request) {
        return ApiResponse.success(inquiryService.create(user.getUserId(), request));
    }

    @GetMapping
    public ApiResponse<PageResponseDto<InquiryResponse>> getMine(
            @AuthenticationPrincipal CustomUserDetails user,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        return ApiResponse.success(inquiryService.getMine(user.getUserId(), page, size));
    }

    @PatchMapping("/{inquiryId}")
    public ApiResponse<InquiryResponse> update(
            @AuthenticationPrincipal CustomUserDetails user,
            @PathVariable @Positive Long inquiryId,
            @Valid @RequestBody InquiryUpdateRequest request) {
        return ApiResponse.success(inquiryService.update(user.getUserId(), inquiryId, request));
    }

    @DeleteMapping("/{inquiryId}")
    public ApiResponse<Void> delete(
            @AuthenticationPrincipal CustomUserDetails user,
            @PathVariable @Positive Long inquiryId) {
        inquiryService.delete(user.getUserId(), inquiryId);
        return ApiResponse.success("문의가 삭제되었습니다.", null);
    }
}
