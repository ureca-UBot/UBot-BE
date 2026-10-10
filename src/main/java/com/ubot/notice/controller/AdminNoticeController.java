package com.ubot.notice.controller;

import java.net.URI;

import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.ubot.auth.config.CustomUserDetails;
import com.ubot.common.ApiResponse;
import com.ubot.common.PageResponseDto;
import com.ubot.notice.dto.request.AdminNoticeRequestDto;
import com.ubot.notice.dto.response.AdminNoticeResponseDto;
import com.ubot.notice.enums.NoticeType;
import com.ubot.notice.service.AdminNoticeService;
import com.ubot.ranking.enums.RegionSido;

import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Positive;
import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/admin/notices")
@RequiredArgsConstructor
@Validated
@Tag(name = "관리자 공지·장애")
public class AdminNoticeController {

    private final AdminNoticeService adminNoticeService;

    @PostMapping
    public ResponseEntity<ApiResponse<AdminNoticeResponseDto>> createNotice(
            @AuthenticationPrincipal CustomUserDetails administrator,
            @Valid @RequestBody AdminNoticeRequestDto request) {
        AdminNoticeResponseDto response = adminNoticeService.createNotice(administrator.getUserId(), request);
        return ResponseEntity.created(URI.create("/admin/notices/" + response.detail().noticeId()))
                .body(ApiResponse.success(response));
    }

    @GetMapping
    public ApiResponse<PageResponseDto<AdminNoticeResponseDto>> getNoticeList(
            @RequestParam(name = "type", required = false) NoticeType type,
            @RequestParam(name = "region", required = false) RegionSido region,
            @RequestParam(name = "deleted", required = false) Boolean deleted,
            @RequestParam(name = "page", defaultValue = "0") @Min(0) int page,
            @RequestParam(name = "size", defaultValue = "20") @Min(1) @Max(100) int size) {
        return ApiResponse.success(adminNoticeService.getNoticeList(type, region, deleted, page, size));
    }

    @GetMapping("/{noticeId}")
    public ApiResponse<AdminNoticeResponseDto> getNotice(@PathVariable(name = "noticeId") @Positive Long noticeId) {
        return ApiResponse.success(adminNoticeService.getNotice(noticeId));
    }

    @PutMapping("/{noticeId}")
    public ApiResponse<AdminNoticeResponseDto> updateNotice(
            @AuthenticationPrincipal CustomUserDetails administrator,
            @PathVariable(name = "noticeId") @Positive Long noticeId,
            @Valid @RequestBody AdminNoticeRequestDto request) {
        return ApiResponse.success(adminNoticeService.updateNotice(administrator.getUserId(), noticeId, request));
    }

    @DeleteMapping("/{noticeId}")
    public ResponseEntity<Void> deleteNotice(
            @AuthenticationPrincipal CustomUserDetails administrator,
            @PathVariable(name = "noticeId") @Positive Long noticeId) {
        adminNoticeService.deleteNotice(administrator.getUserId(), noticeId);
        return ResponseEntity.noContent().build();
    }
}