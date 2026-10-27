package com.ubot.llm.client;

import com.ubot.llm.dto.request.LlmRequestDto;
import com.ubot.llm.dto.response.LlmResponseDto;

public interface LlmClient {

    /**
     * 답변 생성 temperature입니다. Ollama·vLLM 구현체가 모두 이 값을 보냅니다.
     * 지정하지 않으면 모델 기본값(qwen3-instruct는 0.7)이 쓰여 같은 질문에도 답이 달라지므로, LLM_Test 평가와 같은 0으로 고정합니다.
     */
    double TEMPERATURE = 0.0;

    LlmResponseDto generateAnswer(LlmRequestDto request);

    /** 답변 생성 요청에 넣는 모델 이름입니다. 답변 시도 기록(llm_model)에 남길 때 씁니다. */
    String getModelName();
}
