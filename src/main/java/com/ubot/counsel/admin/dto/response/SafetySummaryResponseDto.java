package com.ubot.counsel.admin.dto.response;

public record SafetySummaryResponseDto(long total, long allowed, long blocked, long checkFailed, long falsePositives) {}
