package com.ubot.llm.dto.request;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** 프롬프트 담당이 구성한 메시지를 순서대로 전달합니다. */
public record LlmRequestDto(List<LlmMessageRequestDto> messages) {

    public LlmRequestDto {
        // 유효성은 LlmService에서 검사하고, 호출 중 원본 목록이 바뀌는 것은 방지합니다.
        if (messages != null) {
            messages = Collections.unmodifiableList(new ArrayList<>(messages));
        }
    }
}
