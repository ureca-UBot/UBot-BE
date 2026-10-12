package com.ubot.faq.dto.response;

import java.util.List;

public record FaqAnswerSearchResponseDto(
        List<FaqSearchResponseDto> baseline,
        List<SemanticFaqCandidateResponseDto> semantic
) {
}
