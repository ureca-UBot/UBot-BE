package com.ubot.chat.exception;

import com.ubot.common.ErrorCode;
import com.ubot.llm.enums.LlmErrorCode;
import org.springframework.http.HttpStatus;

// 기존 LLM 오류의 코드와 메시지를 유지하면서 실패 기록에 사용할 공통 규격으로 연결합니다.
public record LlmErrorCodeAdapter(LlmErrorCode source) implements ErrorCode {
	@Override
	public HttpStatus getStatus() {
		return HttpStatus.INTERNAL_SERVER_ERROR;
	}

	@Override
	public String getCode() {
		return source.name();
	}

	@Override
	public String getMessage() {
		return source.getMessage();
	}
}
