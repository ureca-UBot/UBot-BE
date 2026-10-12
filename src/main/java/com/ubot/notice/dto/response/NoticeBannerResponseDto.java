package com.ubot.notice.dto.response;

import java.util.List;

public record NoticeBannerResponseDto(List<NoticeResponseDto> notices, List<NoticeResponseDto> outages) {
}