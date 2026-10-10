package com.ubot.notice.dto.response;

import java.time.LocalDateTime;

import com.ubot.notice.entity.Notice;
import com.ubot.notice.enums.NoticeType;
import com.ubot.ranking.enums.RegionSido;

public record NoticeSummaryResponseDto(Long noticeId, NoticeType type, String title, RegionSido targetRegion,
        LocalDateTime endsAt, LocalDateTime createdAt) {

    public static NoticeSummaryResponseDto from(Notice notice) {
        return new NoticeSummaryResponseDto(
                notice.getNoticeId(),
                notice.getType(),
                notice.getTitle(),
                notice.getTargetRegion(),
                notice.getEndsAt(),
                notice.getCreatedAt());
    }
}