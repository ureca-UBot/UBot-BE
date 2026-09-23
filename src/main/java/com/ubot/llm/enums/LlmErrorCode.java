package com.ubot.llm.enums;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/** 호출자가 채팅 응답 또는 공통 API 오류로 변환할 LLM 모듈의 오류입니다. */
@Getter
@RequiredArgsConstructor
public enum LlmErrorCode {
    LLM_REQUEST_INVALID("LLM 입력 메시지가 올바르지 않습니다."),
    LLM_MODEL_NOT_CONFIGURED("답변 생성에 사용할 LLM 모델이 설정되지 않았습니다."),
    LLM_SERVICE_UNAVAILABLE("LLM 서버에 연결할 수 없습니다. 잠시 후 다시 시도해주세요."),
    LLM_TIMEOUT("LLM 응답 시간이 초과되었습니다. 잠시 후 다시 시도해주세요."),
    LLM_RESPONSE_INVALID("LLM 서버에서 올바른 답변을 받지 못했습니다.");

    private final String message;
}
