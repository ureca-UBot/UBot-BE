package com.ubot.telemap.dto;

import java.time.LocalDateTime;

public record SurgingQuestionSnapshot(
        Long questionLogId,
        String question,
        LocalDateTime occurredAt
) {
}
