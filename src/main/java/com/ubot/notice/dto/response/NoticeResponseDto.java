package com.ubot.notice.dto.response;

import java.time.LocalDateTime;

import com.ubot.notice.entity.Notice;
import com.ubot.notice.enums.NoticeType;
import com.ubot.ranking.enums.RegionSido;

public record NoticeResponseDto(Long noticeId, NoticeType type, String title, String content, RegionSido targetRegion,
        LocalDateTime endsAt, LocalDateTime createdAt) {

    public static NoticeResponseDto from(Notice notice) {
        return new NoticeResponseDto(
                notice.getNoticeId(),
                notice.getType(),
                notice.getTitle(),
                notice.getContent(),
                notice.getTargetRegion(),
                notice.getEndsAt(),
                notice.getCreatedAt());
    }
}