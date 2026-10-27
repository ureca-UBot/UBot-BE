package com.ubot.telemap.dto.response;

import java.time.LocalDateTime;

import com.ubot.ranking.enums.RegionSido;
import com.ubot.telemap.entity.SurgingAlert;
import com.ubot.telemap.enums.SurgingAlertStatus;

public record SurgingAlertSummaryResponse(
        Long alertId,
        RegionSido region,
        String subject,
        int questionCount,
        int windowMinutes,
        SurgingAlertStatus status,
        Long processedById,
        LocalDateTime createdAt,
        LocalDateTime processedAt
) {
    public static SurgingAlertSummaryResponse from(SurgingAlert alert) {
        return new SurgingAlertSummaryResponse(
                alert.getId(),
                alert.getRegion(),
                alert.getSubject(),
                alert.getQuestionCount(),
                alert.getWindowMinutes(),
                alert.getStatus(),
                alert.getUpdatedBy() == null ? null : alert.getUpdatedBy().getId(),
                alert.getCreatedAt(),
                alert.getStatus() == SurgingAlertStatus.PROCESSED ? alert.getUpdatedAt() : null
        );
    }
}
