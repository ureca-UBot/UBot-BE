package com.ubot.llm.dto.response;

/** 모델의 최종 답변만 반환합니다. 추론 과정은 포함하지 않습니다. */
public record LlmResponseDto(String answer) {
}
