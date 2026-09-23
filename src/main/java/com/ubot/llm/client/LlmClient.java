package com.ubot.llm.client;

import com.ubot.llm.dto.request.LlmRequestDto;
import com.ubot.llm.dto.response.LlmResponseDto;

public interface LlmClient {
    LlmResponseDto generateAnswer(LlmRequestDto request);
}
