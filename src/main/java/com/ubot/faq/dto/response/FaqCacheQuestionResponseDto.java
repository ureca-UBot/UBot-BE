package com.ubot.faq.dto.response;

import java.time.LocalDateTime;

/** valid는 현재 프로필·FAQ 승인·내용·버전·질문 벡터가 일치하는지 나타냅니다. */
public record FaqCacheQuestionResponseDto(
        Long id,
        Long faqId,
        Long profileId,
        String question,
        boolean enabled,
        boolean valid,
        int faqVersion,
        int currentFaqVersion,
        LocalDateTime createdAt
) {
}
