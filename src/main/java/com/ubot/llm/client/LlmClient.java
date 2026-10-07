package com.ubot.llm.client;

import com.ubot.llm.dto.request.LlmRequestDto;
import com.ubot.llm.dto.response.LlmResponseDto;

public interface LlmClient {
    LlmResponseDto generateAnswer(LlmRequestDto request);

    /** 답변 생성 요청에 넣는 모델 이름입니다. 답변 시도 기록(llm_model)에 남길 때 씁니다. */
    String getModelName();
}
