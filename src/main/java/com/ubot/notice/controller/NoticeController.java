package com.ubot.notice.controller;

import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.ubot.chat.util.ClientIpResolver;
import com.ubot.chat.util.IpRegionResolver;
import com.ubot.common.ApiResponse;
import com.ubot.common.PageResponseDto;
import com.ubot.notice.dto.response.NoticeBannerResponseDto;
import com.ubot.notice.dto.response.NoticeResponseDto;
import com.ubot.notice.dto.response.NoticeSummaryResponseDto;
import com.ubot.notice.enums.NoticeType;
import com.ubot.notice.service.NoticeService;
import com.ubot.ranking.enums.RegionSido;

import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Positive;
import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/notices")
@RequiredArgsConstructor
@Validated
@Tag(name = "공지·장애 조회")
public class NoticeController {

    private final NoticeService noticeService;
    private final ClientIpResolver clientIpResolver;
    private final IpRegionResolver ipRegionResolver;

    /**
     * 접속 시 보여 줄 공지 배너와 장애 배너를 반환합니다. 비회원도 호출할 수 있습니다.
     * region을 지정하지 않으면 요청 IP로 지역을 판단하고, 판단할 수 없으면 전체 공지만 반환합니다.
     */
    @GetMapping("/banners")
    public ApiResponse<NoticeBannerResponseDto> getBanners(
            @RequestParam(name = "region", required = false) RegionSido region,
            HttpServletRequest httpRequest) {
        RegionSido targetRegion = region != null
                ? region
                : ipRegionResolver.resolve(clientIpResolver.resolve(httpRequest)).orElse(null);
        return ApiResponse.success(noticeService.getBanners(targetRegion));
    }

    @GetMapping
    public ApiResponse<PageResponseDto<NoticeSummaryResponseDto>> getNoticeList(
            @RequestParam(name = "type", required = false) NoticeType type,
            @RequestParam(name = "region", required = false) RegionSido region,
            @RequestParam(name = "page", defaultValue = "0") @Min(0) int page,
            @RequestParam(name = "size", defaultValue = "20") @Min(1) @Max(100) int size) {
        return ApiResponse.success(noticeService.getNoticeList(type, region, page, size));
    }

    @GetMapping("/{noticeId}")
    public ApiResponse<NoticeResponseDto> getNotice(@PathVariable(name = "noticeId") @Positive Long noticeId) {
        return ApiResponse.success(noticeService.getNotice(noticeId));
    }
}