package com.ubot.notice.dto.response;

import java.util.List;

import com.ubot.ranking.enums.RegionSido;

// region은 서버가 접속 IP로 판별한 지역. 판별하지 못하면 null
public record NoticeBannerResponseDto(RegionSido region, List<NoticeResponseDto> notices,
        List<NoticeResponseDto> outages) {
}