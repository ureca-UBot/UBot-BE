package com.ubot.notice.dto.request;

import java.time.LocalDateTime;

import com.ubot.notice.enums.NoticeType;
import com.ubot.ranking.enums.RegionSido;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 공지·장애 등록과 수정에 함께 쓰는 요청입니다. 수정은 전체 교체(PUT)이므로 모든 값을 다시 보냅니다.
 * targetRegion이 null이면 전체 공지, endsAt이 null이면 삭제하기 전까지 계속 노출합니다.
 */
public record AdminNoticeRequestDto(
        @NotNull NoticeType type,
        @NotBlank @Size(max = 100) String title,
        @NotBlank @Size(max = 10000) String content,
        RegionSido targetRegion,
        LocalDateTime endsAt) {
}