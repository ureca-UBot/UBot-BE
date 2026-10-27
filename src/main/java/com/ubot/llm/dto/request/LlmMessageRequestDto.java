package com.ubot.llm.dto.request;

import com.ubot.llm.enums.LlmMessageRole;

public record LlmMessageRequestDto(LlmMessageRole role, String content) {
}
