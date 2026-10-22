package com.ubot.telemap.dto.response;

import java.time.LocalDateTime;
import java.util.List;

import com.ubot.ranking.enums.RegionSido;
import com.ubot.telemap.dto.SurgingQuestionSnapshot;
import com.ubot.telemap.entity.SurgingAlert;
import com.ubot.telemap.enums.SurgingAlertStatus;

public record SurgingAlertDetailResponse(
        Long alertId,
        RegionSido region,
        String subject,
        List<SurgingQuestionSnapshot> questionSnapshots,
        int questionCount,
        int windowMinutes,
        SurgingAlertStatus status,
        Long processedById,
        LocalDateTime createdAt,
        LocalDateTime processedAt
) {
    public static SurgingAlertDetailResponse from(SurgingAlert alert) {
        return new SurgingAlertDetailResponse(
                alert.getId(),
                alert.getRegion(),
                alert.getSubject(),
                alert.getQuestionSnapshots(),
                alert.getQuestionCount(),
                alert.getWindowMinutes(),
                alert.getStatus(),
                alert.getUpdatedBy() == null ? null : alert.getUpdatedBy().getId(),
                alert.getCreatedAt(),
                alert.getStatus() == SurgingAlertStatus.PROCESSED ? alert.getUpdatedAt() : null
        );
    }
}
