package com.ubot.llm.service;

import com.ubot.llm.client.LlmClient;
import com.ubot.llm.dto.request.LlmRequestDto;
import com.ubot.llm.dto.response.LlmResponseDto;
import com.ubot.llm.enums.LlmErrorCode;
import com.ubot.llm.enums.LlmMessageRole;
import com.ubot.llm.exception.LlmException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
@RequiredArgsConstructor
public class LlmService {

    private final LlmClient llmClient;

    /** 검색과 프롬프트 구성은 호출자가 완료한 뒤 전달합니다. */
    public LlmResponseDto generateAnswer(LlmRequestDto request) {
        // API 호출 전에 메시지 누락·공백·마지막 역할을 확인합니다.
        validateRequest(request);
        // 실제 모델 통신은 LlmClient 구현체에 맡깁니다.
        return llmClient.generateAnswer(request);
    }

    private void validateRequest(LlmRequestDto request) {
        if (request == null || request.messages() == null || request.messages().isEmpty()) {
            throw new LlmException(LlmErrorCode.LLM_REQUEST_INVALID);
        }

        for (var message : request.messages()) {
            if (message == null || message.role() == null || !StringUtils.hasText(message.content())) {
                throw new LlmException(LlmErrorCode.LLM_REQUEST_INVALID);
            }
        }

        if (request.messages().getLast().role() != LlmMessageRole.USER) {
            throw new LlmException(LlmErrorCode.LLM_REQUEST_INVALID);
        }
    }
}
