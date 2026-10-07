package com.ubot.llm.service;

import com.ubot.llm.client.LlmClient;
import com.ubot.llm.dto.request.LlmRequestDto;
import com.ubot.llm.dto.response.LlmResponseDto;
import com.ubot.llm.exception.LlmErrorCode;
import com.ubot.llm.enums.LlmMessageRole;
import com.ubot.llm.exception.LlmException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
@RequiredArgsConstructor
@Slf4j
public class LlmService {

    private final LlmClient llmClient;

    /** 검색과 프롬프트 구성은 호출자가 완료한 뒤 전달합니다. */
	public LlmResponseDto generateAnswer(LlmRequestDto request) {
        // API 호출 전에 메시지 누락·공백·마지막 역할을 확인합니다.
        validateRequest(request);
        // 실제 모델 통신은 LlmClient 구현체에 맡깁니다.
		long startedAt = System.nanoTime();
		try {
			LlmResponseDto response = llmClient.generateAnswer(request);
			if(response == null)
				throw new LlmException(LlmErrorCode.LLM_RESPONSE_INVALID);

			log.debug("LLM 요청을 완료했습니다: 처리시간={}ms, 응답길이={}", elapsedMillis(startedAt),
					response.answer() == null ? 0 : response.answer().length());
			return response;
		} catch (LlmException exception) {
			log.warn("LLM 요청에 실패했습니다: 오류코드={}, 오류메시지={}, 처리시간={}ms",
					exception.getErrorCode().getCode(), exception.getErrorCode().getMessage(), elapsedMillis(startedAt));
			throw exception;
		}
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

        // 도구 메서드가 ToolContext를 받으면 Spring AI는 빈 문맥으로 도구를 실행하지 않고 예외를 던집니다.
        if (request.hasTools() && request.toolContext().isEmpty()) {
            throw new LlmException(LlmErrorCode.LLM_REQUEST_INVALID);
        }
    }

	private long elapsedMillis(long startedAt) {
		return (System.nanoTime() - startedAt) / 1_000_000;
	}
}
