package com.ubot.notice.dto.response;

import com.ubot.common.dto.response.AdminMetadataResponseDto;
import com.ubot.notice.entity.Notice;

public record AdminNoticeResponseDto(NoticeResponseDto detail, AdminMetadataResponseDto metadata) {

    public static AdminNoticeResponseDto from(Notice notice) {
        return new AdminNoticeResponseDto(NoticeResponseDto.from(notice), AdminMetadataResponseDto.from(notice));
    }
}