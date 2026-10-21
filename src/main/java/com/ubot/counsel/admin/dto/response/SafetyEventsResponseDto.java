package com.ubot.counsel.admin.dto.response;

import java.util.List;

public record SafetyEventsResponseDto(List<SafetyEventResponseDto> items, int page, boolean hasNext) {}
