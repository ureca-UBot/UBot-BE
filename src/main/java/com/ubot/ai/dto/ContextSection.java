package com.ubot.ai.dto;

import org.springframework.util.StringUtils;

/** FAQ 외에 프롬프트에 넣을 참고 자료 한 덩어리입니다. 예: ("사용자 정보", "...") */
public record ContextSection(String title, String content) {

    public ContextSection {
        if (!StringUtils.hasText(title) || !StringUtils.hasText(content)) {
            throw new IllegalArgumentException("참고 자료의 제목과 내용이 필요합니다.");
        }
    }
}
