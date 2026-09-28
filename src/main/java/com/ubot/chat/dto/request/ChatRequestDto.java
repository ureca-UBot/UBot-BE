package com.ubot.chat.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

// 질문자 ID는 요청 본문 대신 로그인 인증 정보에서 확인합니다.
public record ChatRequestDto(
		@NotBlank
		@Size(max = 4000)
		String question
) {
}
