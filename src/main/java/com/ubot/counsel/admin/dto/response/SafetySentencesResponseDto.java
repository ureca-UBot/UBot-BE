package com.ubot.counsel.admin.dto.response;

import java.util.List;

public record SafetySentencesResponseDto(List<SafetySentenceResponseDto> items, int page, boolean hasNext) {}
