package com.ubot.chat.dto.response;

public record ChatResponseDto(String answer, boolean success) {

    public static ChatResponseDto createSuccessAnswer(String answer) {
        return new ChatResponseDto(answer, true);
    }

    public static ChatResponseDto createFailureAnswer(String reason) {
        return new ChatResponseDto(reason, false); // 실패 이유 작성
    }
}
